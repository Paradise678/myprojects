package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


/**
 * 【刷新 Token 拦截器】—— 第一层拦截器（拦截所有路径）
 *
 * ⭐ 这个拦截器的核心职责：【给"活跃用户"的 Token 续期】
 *
 * 设计思想：
 *   如果只用一个 LoginInterceptor，会有个体验问题：
 *   → 用户在首页（放行路径）浏览店铺列表的时候，Token 可能悄悄过期了
 *   → 等用户点"下单"（需要登录的路径）时，突然被踢回登录页
 *   → 体验极差！
 *
 *   所以项目用了【两层拦截器】配合：
 *     第一层 RefreshTokenInterceptor  → 拦截【所有】路径
 *       → 只要用户带了合法 Token，就刷新过期时间 + 存 ThreadLocal
 *       → 即使是首页这种"放行路径"也执行，保证活跃用户 Token 不过期
 *     第二层 LoginInterceptor        → 只拦截【需要登录】的路径
 *       → 强制检查 ThreadLocal 里有没有用户
 *       → 没有就返回 401
 *
 *   两层配合的效果：
 *     ✅ 用户活跃（不管访问什么页面）→ Token 自动续期，永不掉线
 *     ✅ 用户真的没登录 → 第一层放行为 true，第二层拦下来返回 401
 *     ✅ 用户 Token 过期了 → 第一层查 Redis 发现没数据，放行为 true，第二层拦返回 401
 *
 * implements HandlerInterceptor —— Spring MVC 拦截器接口
 *   有 3 个方法：preHandle（前置）/ postHandle（中间）/ afterCompletion（后置）
 *   本类覆写了 preHandle 和 afterCompletion，postHandle 没用所以不写
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    // ====================================================================
    // 为什么用构造器注入而不是 @Resource / @Autowired 字段注入？
    //
    // 因为这个类【不是由 Spring 容器管理的】！
    // 在 MvcConfig 里是这样注册的：
    //   registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate));
    //                                          ↑ 自己 new 的，不是 Spring 注入的
    //
    // 那 stringRedisTemplate 从哪来？是 MvcConfig 里通过 @Resource 注入好的，
    // 然后传给这个构造器。所以这里必须用构造器接收。
    //
    // 对比 LoginInterceptor：
    //   registry.addInterceptor(new LoginInterceptor());
    //                          ↑ 没传任何参数，因为 LoginInterceptor 里没用到 Bean
    //
    // 如果 RefreshTokenInterceptor 里写 @Resource 注入 stringRedisTemplate：
    // ❌ 会 NPE！因为 Spring 不会去管理 new 出来的对象，@Resource 不会生效
    // ====================================================================

    private StringRedisTemplate stringRedisTemplate;  // Redis 操作模板

    /**
     * 构造器注入
     * @param stringRedisTemplate 由 MvcConfig（Spring 管理的配置类）传入
     */
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    // ==================== preHandle：请求进入 Controller 之前执行 ====================
    // 返回 true  → 放行，继续往下走（可能到下一个拦截器，也可能到 Controller）
    // 返回 false → 中断，请求到此为止

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // ==================== 第 1 步：从请求头拿 Token ====================

        // request.getHeader("authorization") —— 从 HTTP 请求头中获取名为 "authorization" 的值
        // 这个 Token 是前端登录成功后存到 localStorage，后续每次请求手动放到 Header 里的
        // 注意：HTTP Header 的 key 不区分大小写，Authorization / authorization 都能拿到
        String token = request.getHeader("authorization");

        // StrUtil.isBlank(token) —— Hutool 工具类，判断字符串是否为空
        // 比 token == null || token.isEmpty() 更强大：
        //   null     → true
        //   ""       → true
        //   "   "    → true（全是空格也算空）
        //   "abc"    → false
        if (StrUtil.isBlank(token)) {
            // 没有 Token → 说明用户可能没登录
            // 直接返回 true 放行！让请求继续往下走
            // 为什么不直接拦截？因为：
            //   ① 有可能这个接口本身就不需要登录（比如首页店铺列表）
            //   ② 即使需要登录，还有第二层 LoginInterceptor 会拦
            //
            // ⚠️ 这就是两层拦截器设计的精妙之处：
            //   RefreshTokenInterceptor 只做"刷新 + 存用户"的事，不做拦截决策
            //   拦截决策交给第二层 LoginInterceptor，职责分离
            return true;
        }

        // ==================== 第 2 步：根据 Token 去 Redis 查用户 ====================

        // 拼 Redis Key："login:token:" + "a1b2c3d4e5f6..."
        String key = LOGIN_USER_KEY + token;

        // stringRedisTemplate.opsForHash().entries(key) —— 读取 Redis Hash 中所有字段
        // 等价于 Redis 命令：HGETALL login:token:a1b2c3d4...
        // 返回一个 Map，key 是 field（id、nickName、icon），value 是对应的字符串值
        // 如果 key 不存在（Token 无效或过期），返回空 Map {}
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // ==================== 第 3 步：判断用户是否存在 ====================

        // userMap.isEmpty() —— Map 为空说明 Redis 里没这个 key
        // 可能的原因：
        //   ① Token 错了 → key 拼出来不对
        //   ② Token 过期了 → Redis 自动删了
        //   ③ 用户没登录，但带了个假 Token
        if (userMap.isEmpty()) {
            // Token 无效，放行为 true
            // 让请求继续往下走，第二层 LoginInterceptor 会拦下来返回 401
            return true;
        }

        // ==================== 第 4 步：Map → UserDTO ====================

        // BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false) —— Hutool 工具类
        // 把 Map 的键值对填入 Java Bean 对象
        //   userMap        —— 源数据 Map：{ "id": "1", "nickName": "用户aB3x", "icon": "" }
        //   new UserDTO()  —— 目标对象（空的，等下往里填）
        //   false          —— 是否忽略空值（false = 不忽略，即使是 "" 也填进去）
        //
        // 和 login 方法里的 BeanUtil.copyProperties 对比：
        //   copyProperties  是 Bean → Bean（比如 User → UserDTO）
        //   fillBeanWithMap 是 Map  → Bean（这里是 Redis 查出来的 Hash 数据 → UserDTO）
        //
        // 转换过程：
        //   userMap 的 key "id"    → 匹配 UserDTO 的 id 字段    → 把 value "1" 转成 Long 填入
        //   userMap 的 key "nickName" → 匹配 UserDTO 的 nickName 字段 → 填入 "用户aB3x"
        //   userMap 的 key "icon"  → 匹配 UserDTO 的 icon 字段  → 填入 ""
        //
        // ⚠️ 为什么这里能自动做 String → Long 的类型转换？
        // Hutool 内部会根据目标字段的类型自动做类型转换：
        //   Map 里 id 是 String "1" → UserDTO.id 是 Long 1L → 自动转了
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // ==================== 第 5 步：存入 ThreadLocal ====================

        // UserHolder 是项目自定义的 ThreadLocal 工具类
        // 内部封装了 ThreadLocal<UserDTO>，提供 saveUser / getUser / removeUser 三个静态方法
        //
        // 为什么存 ThreadLocal？因为 Controller/Service 里想拿当前用户时：
        //   ❌ 不要每次都 request.getHeader() → 拼 key → 查 Redis → 转 Map → 转 UserDTO
        //      太麻烦了，每个接口都要写一遍
        //   ✅ 直接 UserHolder.getUser() 一行搞定
        //      ThreadLocal 让数据在当前线程内共享，同一个请求从头到尾都能拿到
        //
        // ThreadLocal 的特性：
        //   ① 线程隔离：每个线程有自己独立的副本，不会串
        //   ② Tomcat 线程池：同一请求从头到尾在同一个线程执行，所以能一直拿到
        UserHolder.saveUser(userDTO);

        // ==================== 第 6 步：刷新 Token 过期时间 ⭐ 核心！====================

        // stringRedisTemplate.expire(key, timeout, timeUnit) —— 重置 key 的过期时间
        //   key     = "login:token:a1b2c3d4..."
        //   timeout = LOGIN_USER_TTL = 30L
        //   timeUnit = TimeUnit.MINUTES
        //
        // 等价于 Redis 命令：EXPIRE login:token:a1b2c3d4 1800
        // 从【这一刻】开始重新计时 30 分钟
        //
        // 这就是"滑动过期"机制：
        //   用户只要还在操作（发请求）→ 每次请求都会执行到这里 → Token 一直续期
        //   用户停止操作超过 30 分钟 → 过期时间到了，Redis 自动删 key → Token 失效
        //
        // 为什么刷新不放在 LoginInterceptor 里？
        //   因为 LoginInterceptor 只拦截"需要登录的路径"
        //   如果用户一直在浏览不需要登录的页面（比如首页、店铺列表），
        //   Token 就不会被刷新 → 悄悄过期 → 点"下单"时突然掉线
        //   RefreshTokenInterceptor 拦截所有路径，完美解决这个问题
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // ==================== 第 7 步：放行 ====================
        return true;
    }


    // ==================== afterCompletion：整个请求结束后执行 ====================
    // 无论 Controller 正常执行还是抛异常，这个方法【一定会被调用】
    // （但注意：如果 preHandle 返回 false，afterCompletion 不会执行）

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        // 清理 ThreadLocal 中的用户信息
        // ⚠️ 极其重要！必须清理！
        //
        // 原因：Tomcat 线程池复用
        //   Tomcat 不会为每个请求创建新线程，而是从线程池里拿线程来处理
        //   如果当前请求用完 ThreadLocal 不 remove：
        //     线程 A 处理完用户1的请求 → ThreadLocal 还残留用户1的数据
        //     线程 A 下次被分配给用户2的请求 → preHandle 先存了用户2 → 正常
        //     但如果中间有异步操作 / 子线程 / 某些边界情况 → 可能拿到脏数据
        //
        // ThreadLocal 的黄金法则：【Request 结束时务必 remove】
        //
        // 两个拦截器都要清理：
        //   RefreshTokenInterceptor.afterCompletion → 清理 ThreadLocal
        //   LoginInterceptor.afterCompletion        → 也清理 ThreadLocal
        //   不管哪个拦截器先执行，最终 ThreadLocal 都会被清空
        UserHolder.removeUser();
    }
}