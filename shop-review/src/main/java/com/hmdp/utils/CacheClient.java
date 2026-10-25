package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;


/**
 * 缓存客户端工具类
 *
 * 这个类封装了 Redis 缓存操作的通用逻辑，提供了三种缓存问题的解决方案：
 *   ① queryWithPassThrough      —— 解决【缓存穿透】
 *   ② queryWithMutex            —— 解决【缓存击穿】（互斥锁方式）
 *   ③ queryWithLogicalExpire    —— 解决【缓存击穿】（逻辑过期方式）
 *
 * 为什么要单独封装？
 *   如果每个 Service 都自己写缓存逻辑，代码会大量重复
 *   封装成工具类后，业务代码只需一行调用即可完成"查缓存→查数据库→写缓存"的完整流程
 */
@Slf4j      // Lombok 注解：自动生成 private static final Logger log 对象
@Component  // Spring 注解：把当前类注册为 Spring Bean，其他地方可以 @Resource 注入
public class CacheClient {

    // ====================================================================
    // 构造器注入
    // ====================================================================

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 固定大小为 10 的线程池
     * 用于"逻辑过期"方案中的【异步缓存重建】
     * 当缓存过期时，不会阻塞用户线程，而是丢到这个线程池里异步重建缓存
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 构造器注入（推荐的注入方式，比 @Resource 字段注入更好）
     * @param stringRedisTemplate Spring 自动注入的 Redis 操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    // ====================================================================
    // 基础方法：写缓存
    // ====================================================================

    /**
     * 设置缓存（带过期时间，物理过期）
     *
     * @param key    Redis 的 key，比如 "cache:shop:1"
     * @param value  要存的值（任意对象，会自动转成 JSON 字符串），比如 Shop 对象
     * @param time   过期时间数值，比如 10L
     * @param unit   时间单位，比如 TimeUnit.MINUTES
     *
     * 底层等价于 Redis 命令：SETEX key seconds value
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        // JSONUtil.toJsonStr(value) —— Hutool 工具类，把 Java 对象转成 JSON 字符串
        //   为什么要转？因为 StringRedisTemplate 只能存 String 类型
        //   Shop 对象 → '{"id":1,"name":"奶茶店","address":"..."}'
        //
        // .set(key, json, time, unit) —— 存值并设置物理过期时间
        //   时间到了 Redis 自动删除这个 key
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 设置缓存（逻辑过期方式）
     *
     * 和 set() 的区别：
     *   set()         —— Redis 物理删除 key（时间到了 key 直接没了）
     *   setWithLogicalExpire() —— key 永远不会被 Redis 删除，把过期时间存在 value 里
     *                             读到数据时手动判断是否过期
     *
     * 为什么需要逻辑过期？
     *   物理过期时，如果大量 key 同时过期（缓存雪崩），所有请求都会打到数据库
     *   逻辑过期可以让"过期"的缓存继续返回旧数据，同时异步重建，用户无感知
     *
     * @param key    Redis 的 key
     * @param value  要存的值
     * @param time   过期时间数值
     * @param unit   时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // RedisData 是项目自定义的包装类，包含两个字段：
        //   data       —— 真正要存的业务数据（比如 Shop 对象）
        //   expireTime —— 逻辑过期时间（LocalDateTime 类型）
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 当前时间 + 过期时长 = 未来的某个时刻
        // unit.toSeconds(time) 把时间转成秒，再加到当前时间上
        // 比如：现在是 10:00，time=20L，unit=SECONDS → expireTime = 10:00:20
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 把 RedisData 对象整体转成 JSON 存入 Redis
        // ⚠️ 注意：这里【没有设置物理过期时间】！
        //   这个 key 在 Redis 中永不过期，过期判断由业务代码自己做
        //   JSON 结构示例：
        //   {
        //     "data": {"id":1, "name":"奶茶店"},
        //     "expireTime": "2025-09-02T10:00:20"
        //   }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    // ====================================================================
    // 方案一：缓存穿透 —— 查不到的数据也缓存空值
    // ====================================================================
    //
    // 什么是缓存穿透？
    //   查询一个【数据库里根本不存在】的数据
    //   Redis 查不到 → 穿透到数据库 → 数据库也查不到 → 返回 null
    //   如果有人恶意用大量不存在的 id 疯狂请求 → 数据库压力暴增
    //   （比如循环请求 id = -1, -2, -3 ...）
    //
    // 解决方案：缓存空值
    //   数据库查不到 → 也往 Redis 里存一个空字符串 ""，并设置较短的过期时间
    //   下次再查同一个 id → Redis 命中空值 → 直接返回 null，不再打数据库
    //
    // 三种查询结果的处理：
    //   ① Redis 命中 + 有值   → 直接返回 ✅
    //   ② Redis 命中 + 空值   → 直接返回 null（防穿透）✅
    //   ③ Redis 未命中        → 查数据库 → 有值则写缓存；无值则写空值缓存 ✅

    /**
     * 缓存穿透解决方案
     *
     * @param keyPrefix   Redis key 前缀，比如 "cache:shop:"
     * @param id          查询的 id，比如 1L
     * @param type        返回值类型，比如 Shop.class（用于 JSON 反序列化）
     * @param dbFallback  数据库查询函数（函数式编程，传入 id 返回 R）
     *                    调用方传 this::getById，就是 MyBatis-Plus 的 getById 方法
     * @param time        缓存过期时间
     * @param unit        时间单位
     * @param <R>         返回值泛型（比如 Shop）
     * @param <ID>        id 的泛型（比如 Long）
     * @return            查询到的对象，或 null
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        // 拼 Redis key："cache:shop:" + 1
        String key = keyPrefix + id;

        // 1. 从 Redis 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 Redis 中是否有数据
        // StrUtil.isNotBlank(json) —— Hutool 工具类，判断字符串是否"有内容"
        //   null        → false
        //   ""          → false
        //   "   "       → false
        //   "abc"       → true
        if (StrUtil.isNotBlank(json)) {
            // 3. 命中缓存且有值 → 直接反序列化返回
            //    JSONUtil.toBean(json, type) —— 把 JSON 字符串转成 Java 对象
            //    比如：'{"id":1,"name":"奶茶店"}' → Shop 对象
            return JSONUtil.toBean(json, type);
        }

        // 4. 判断是否命中了【空值缓存】
        // json != null 说明 key 存在（只是值为空字符串 ""）
        // StrUtil.isNotBlank(json) 刚才已经是 false 了
        // 所以这里的逻辑是：json 不是 null，但又是空白字符串 → 命中了空值缓存
        if (json != null) {
            // 命中空值缓存 → 说明数据库里确实没有这条数据
            // 直接返回 null，不再查询数据库
            return null;
        }

        // 5. Redis 中完全没有这个 key → 查询数据库
        //    dbFallback.apply(id) —— 执行传入的数据库查询函数
        //    比如调用方传了 this::getById，这里等价于 shopService.getById(id)
        //    Function<ID, R> 是 Java 8 函数式接口，apply 方法接收参数返回结果
        R r = dbFallback.apply(id);

        // 6. 数据库也没查到
        if (r == null) {
            // 把空值写入 Redis，缓存空值！
            //   key   = "cache:shop:-1"
            //   value = "" （空字符串）
            //   TTL   = CACHE_NULL_TTL（一般是 2 分钟，比正常缓存短很多）
            // 为什么要短？因为空值缓存不是真实数据，过期后可以重新查数据库
            // （万一后来有人插入了这条数据呢）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 7. 数据库查到了 → 写入 Redis 缓存（用前面的 set 方法）
        this.set(key, r, time, unit);
        return r;
    }


    // ====================================================================
    // 方案二：缓存击穿 —— 逻辑过期（异步线程重建，用户无感知）
    // ====================================================================
    //
    // 什么是缓存击穿？
    //   某个【热点 key】过期的瞬间，大量并发请求同时到达
    //   因为缓存没了 → 全部打到数据库 → 数据库瞬间被压垮
    //   （比如秒杀活动的商品详情、热门店铺）
    //
    //   注意区分：
    //   缓存穿透 —— 查【不存在】的数据（缓存和数据库都没有）
    //   缓存击穿 —— 查【存在的热点】数据，但缓存【过期】了
    //   缓存雪崩 —— 大量 key【同时过期】（大面积击穿）
    //
    // 逻辑过期方案的思路：
    //   Redis 中的 key【永不过期】，把过期时间存在 value 里
    //   读到数据时发现过期了：
    //     → 返回旧数据（用户无感知，不会报错）
    //     → 异步开一个线程去重建缓存（查数据库 + 写 Redis）
    //     → 用互斥锁保证【只有一个线程】去重建缓存（避免大量线程同时打数据库）
    //
    //   好处：用户永远不会看到 500 或空数据
    //   坏处：需要额外的内存存旧数据（Redis key 永不过期）

    /**
     * 缓存击穿解决方案 - 逻辑过期方式
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;

        // 1. 从 Redis 查询
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 Redis 中是否有数据
        if (StrUtil.isBlank(json)) {
            // Redis 中没有 → 说明还没预热过（逻辑过期方案需要先手动预热数据）
            // 直接返回 null，不查数据库（逻辑过期方案假设数据已经预热在 Redis 中了）
            return null;
        }

        // 3. 命中！反序列化成 RedisData 对象
        //    RedisData 包含两部分：data（业务数据） + expireTime（逻辑过期时间）
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);

        // 4. 取出业务数据部分
        //    redisData.getData() 返回的是 Object 类型（因为可以存任意类型）
        //    (JSONObject) 强转是因为 Hutool 反序列化后默认用 JSONObject 表示嵌套对象
        //    然后再用 JSONUtil.toBean 把 JSONObject 转成真正的 Shop 对象
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 5. 取出逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        // 6. 判断是否过期
        //    isAfter(LocalDateTime.now()) —— 过期时间在当前时间之后吗？
        //    如果是 → 还没过期，正常返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期 → 直接返回，不需要重建
            return r;
        }

        // ==================== 走到这里说明：缓存已过期，需要重建 ====================

        // 7. 尝试获取互斥锁（避免多个线程同时重建）
        String lockKey = LOCK_SHOP_KEY + id;  // 比如 "lock:shop:1"
        boolean isLock = tryLock(lockKey);

        // 8. 获取锁成功的线程 → 负责异步重建缓存
        if (isLock) {
            // 9. 提交一个异步任务到线程池
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 9.1 查数据库（拿到最新数据）
                    R newR = dbFallback.apply(id);
                    // 9.2 用逻辑过期方式写入 Redis
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    // 重建过程中出异常（比如数据库挂了）
                    throw new RuntimeException(e);
                } finally {
                    // 10. 无论成功还是失败，都要释放锁！
                    unlock(lockKey);
                }
            });
        }

        // 11. 不管有没有拿到锁，都返回【过期的旧数据】
        //     拿到锁的线程在异步重建，没拿到锁的线程等下一次请求就会命中新缓存
        //     用户完全无感知！这就是逻辑过期方案的核心优势
        return r;
    }


    // ====================================================================
    // 方案三：缓存击穿 —— 互斥锁（同步方式，等缓存重建完再返回）
    // ====================================================================
    //
    // 逻辑过期方案虽然用户无感知，但需要【提前预热数据】且【Redis key 永不过期】
    // 互斥锁方案更简单：
    //   缓存没了 → 尝试获取锁 → 拿到锁的线程去查数据库 + 写缓存
    //   没拿到锁的线程 → 休眠 50ms → 重试（递归调用自己）
    //
    //   好处：不需要预热，数据自然过期
    //   坏处：没拿到锁的线程会阻塞等待（有性能损耗）

    /**
     * 缓存击穿解决方案 - 互斥锁同步方式
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;

        // 1. 查 Redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中且有值 → 直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }

        // 3. 命中空值缓存 → 返回 null（防穿透）
        if (shopJson != null) {
            return null;
        }

        // ==================== 走到这里：缓存没了，需要重建 ====================

        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;

        try {
            // 4. 尝试获取锁
            boolean isLock = tryLock(lockKey);

            // 5. 没拿到锁 → 休眠 50ms → 重试
            if (!isLock) {
                // Thread.sleep(50) —— 让出 CPU 50 毫秒，避免空转消耗 CPU
                Thread.sleep(50);
                // 递归调用自己！重新走一遍查询流程
                // 此时 Redis 缓存可能已经被拿到锁的线程重建好了
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }

            // 6. 拿到锁了 → 查数据库
            r = dbFallback.apply(id);

            // 7. 数据库也没查到 → 存空值缓存
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 8. 查到了 → 写入 Redis
            this.set(key, r, time, unit);

        } catch (InterruptedException e) {
            // Thread.sleep 可能被中断
            throw new RuntimeException(e);
        } finally {
            // 9. 释放锁（无论成功失败）
            unlock(lockKey);
        }

        // 10. 返回结果
        return r;
    }


    // ====================================================================
    // 私有方法：简单的分布式锁实现
    // ====================================================================

    /**
     * 尝试获取锁（SETNX 原子操作）
     *
     * Redis SETNX 命令 = SET if Not eXists
     *   只有当 key 不存在时才设置成功
     *   返回 true 表示获取锁成功
     *
     * @param key 锁的 key，比如 "lock:shop:1"
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        // setIfAbsent —— Spring Data Redis 对 SETNX 的封装
        // 参数：key, value, 过期时间, 时间单位
        // 设置过期时间很重要！防止死锁
        //   比如拿到锁的线程挂了 → 锁自动过期 → 其他线程能继续获取
        //   这里设 10 秒，足够完成数据库查询 + 缓存重建了
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        // BooleanUtil.isTrue(flag) —— 防止 flag 为 null 时自动拆箱 NPE
        //   setIfAbsent 返回的是 Boolean 对象（可能为 null）
        //   直接写 return flag; 如果 flag 是 null 会报 NullPointerException
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁（简单的 DEL 操作）
     * @param key 锁的 key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}