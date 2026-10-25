package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


/**
 * 店铺 Service 实现类
 *
 * 继承 ServiceImpl<ShopMapper, Shop>：
 *   ShopMapper —— MyBatis Mapper 接口，负责 SQL 映射
 *   Shop       —— 数据库实体类，对应 tb_shop 表
 *   继承后免费获得 selectById / save / updateById 等基础 CRUD
 *
 * implements IShopService：
 *   实现自定义的业务接口，面向接口编程
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /**
     * 通过 Spring 自动注入的 StringRedisTemplate 工具类，
     * 让 ShopServiceImpl 能直接操作 Redis（主要用来在店铺更新后删除缓存），
     * 是项目「写操作删缓存」架构的核心支撑。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 缓存客户端工具类（注入 Spring Bean）
     * 封装了三种缓存策略，查询时直接调用它的方法即可
     */
    @Resource
    private CacheClient cacheClient;

    /**
     * 店铺本地缓存（L1，Caffeine）：进程内缓存，命中不产生网络开销。
     * Redis（L2）→ MySQL（DB）构成三级结构。
     */
    @Resource
    private Cache<Long, Shop> shopLocalCache;

    /**
     * 延迟双删的延迟删除调度器
     */
    @Resource
    private ScheduledExecutorService cacheDeleteScheduler;

    /**
     * 延迟双删的延迟时间：等并发读请求把"删缓存后回源查到的旧值"写回缓存后，
     * 再删一次，把脏缓存清掉
     */
    private static final long DELAYED_DELETE_MS = 500L;


    /**
     * 根据 id 查询店铺（带缓存）
     *
     * 业务逻辑：先查 Redis 缓存 → 命中直接返回 → 未命中查数据库 → 写入缓存
     *
     * @param id 店铺 id
     * @return 统一响应体（包含店铺数据）
     */
    @Override
    public Result queryById(Long id) {

        // ==================== 第 1 级：本地缓存（Caffeine） ====================
        // 命中：纳秒级返回，不访问 Redis，热点 Key 高 QPS 下保护 Redis 带宽/CPU
        Shop shop = shopLocalCache.getIfPresent(id);

        // ==================== 第 2 级：Redis（含穿透/击穿保护） ====================
        if (shop == null) {
            // 使用 queryWithPassThrough 解决缓存穿透问题
            // 调用参数拆解：
            //   CACHE_SHOP_KEY        —— Redis key 前缀 "cache:shop:"
            //   id                    —— 店铺 id，比如 1L
            //   Shop.class            —— 返回值类型（用于 JSON 反序列化）
            //   this::getById         —— 数据库查询方法引用（等价于 id -> this.getById(id)）
            //   CACHE_SHOP_TTL        —— 缓存过期时间（常量，比如 10L）
            //   TimeUnit.MINUTES      —— 时间单位：分钟
            shop = cacheClient.queryWithPassThrough(
                    CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

            // Redis 命中则回填本地缓存（L1），下次同实例请求直接进程内返回
            if (shop != null) {
                shopLocalCache.put(id, shop);
            }
        }

        // ==================== 以下两种方案注释掉了，按需切换 ====================

        // 方案二：互斥锁解决缓存击穿（同步阻塞方式）
        // 特点：缓存过期时，只有一个线程查数据库，其他线程等待
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 方案三：逻辑过期解决缓存击穿（异步非阻塞方式）
        // 特点：缓存过期时，返回旧数据 + 异步重建，用户无感知
        // 注意：使用此方案需要先手动预热数据到 Redis 中！
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        // 查询结果为 null → 店铺不存在（本地不缓存 null，穿透由 Redis 空值兜底）
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        // 有数据 → 返回
        return Result.ok(shop);
    }


    /**
     * 更新店铺信息（延迟双删：事务提交后删缓存 + 500ms 后再删一次）
     *
     * 为什么不是"先删缓存，再更新数据库"？
     *   反例：
     *   线程 A（写）：删缓存 → ...还没更新数据库...
     *   线程 B（读）：缓存没了 → 查数据库（读到旧值）→ 写入缓存（旧值）
     *   线程 A（写）：更新数据库
     *   结果：Redis 里是旧数据，数据库里是新数据 → 数据不一致！
     *
     * 为什么"先更新数据库，再删缓存"还需要延迟双删？
     *   基础版仍有一个极小的脏读窗口：
     *   线程 B（读）：缓存没了 → 查数据库（恰好在更新前读到旧值）→ 还没写缓存
     *   线程 A（写）：更新数据库 → 删缓存（此时缓存本来就是空的）
     *   线程 B（读）：把旧值写入缓存 → 缓存里是旧数据！
     *   解决：删除后延迟一小段时间（500ms）再删一次，把线程 B 写入的旧值清掉
     *
     * 为什么在事务提交后（afterCommit）才删，而不是方法内直接删？
     *   如果删缓存发生在事务提交前，另一个读请求会立刻回源——
     *   但此时旧事务还没提交，读到的是旧值，又被写回缓存，
     *   相当于白删；事务提交后再删才能保证删除发生在新数据生效之后
     *
     * 多级缓存一致性：
     *   本地缓存（L1）与 Redis（L2）同时删除；
     *   多实例部署时其他实例的 L1 无法直接删除，靠 5s TTL 兜底（见 LocalCacheConfig）
     */
    @Override
    @Transactional
    public Result update(Shop shop) {

        // 防御性检查：id 不能为空（否则不知道更新哪条）
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1. 先更新数据库（MyBatis-Plus 通用方法）
        updateById(shop);

        // 2. 注册事务同步回调：等事务真正提交成功后再删缓存
        //    （如果事务回滚，缓存不会被误删，避免无意义的缓存重建）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictShopCache(id);

                // 3. 延迟双删：500ms 后再删一次，
                //    清理"并发读请求回源旧值后写回缓存"造成的脏数据
                cacheDeleteScheduler.schedule(
                        () -> evictShopCache(id), DELAYED_DELETE_MS, TimeUnit.MILLISECONDS);
            }
        });

        return Result.ok();
    }

    /**
     * 同时失效两级缓存：本地 Caffeine + Redis
     */
    private void evictShopCache(Long id) {
        shopLocalCache.invalidate(id);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}