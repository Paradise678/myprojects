package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;


/**
 * Spring MVC 配置类
 *
 * @Configuration —— Spring 注解，标记这是一个【配置类】
 *   Spring Boot 启动时会自动扫描并加载所有 @Configuration 类
 *   相当于传统 SSM 项目中的 springmvc.xml 配置文件的 Java Config 写法
 *
 * implements WebMvcConfigurer —— Spring MVC 提供的【配置接口】
 *   接口里全是 default 方法（有默认空实现），按需覆写即可
 *   这个接口能配置：拦截器、跨域、静态资源、视图控制器、消息转换器 等
 *   本类只用到了 addInterceptors 一个方法
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /**
     * 注入 StringRedisTemplate
     *
     * @Resource —— JSR-250 规范注解（也可以用 @Autowired）
     *   Spring 启动时，IoC 容器里已经有了 StringRedisTemplate Bean
     *   （因为 pom.xml 引入了 spring-boot-starter-data-redis，Spring Boot 自动配置的）
     *   @Resource 告诉 Spring："请把容器里的 StringRedisTemplate 注入给我"
     *
     * 为什么需要注入它？因为 RefreshTokenInterceptor 要用！
     * 但是 RefreshTokenInterceptor 不是 Spring 管理的 Bean（是我们手动 new 的），
     * 所以没法在它内部 @Resource 注入。只能在这里注入好，然后通过构造器传进去。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 注册拦截器
     *
     * @param registry 拦截器注册器，Spring MVC 提供的对象
     *                 通过它可以 addInterceptor() 注册拦截器，
     *                 然后链式调用配置拦截路径、排除路径、执行顺序等
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // ==================== 第一层：RefreshTokenInterceptor ====================
        // 核心职责：刷新 Token 有效期 + 存 ThreadLocal
        // 特点：拦截所有路径，放行为 true（不做拦截决策）

        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                // ↑ addInterceptor() —— 向注册器中添加一个拦截器
                //   new RefreshTokenInterceptor(stringRedisTemplate)
                //   手动创建拦截器实例，并把上面 @Resource 注入的 stringRedisTemplate 传进去
                //   （因为 RefreshTokenInterceptor 内部要用它查 Redis）

                .addPathPatterns("/**")
                // ↑ addPathPatterns() —— 配置【要拦截】的路径
                //   "/**" 是 Ant 风格通配符，意思是【所有路径，无论多少层】
                //   为什么拦截所有？因为刷新 Token 需要覆盖用户的每一次操作
                //   即使是首页这种不需要登录的路径，用户访问了也说明还活跃，Token 应该续期
                //
                //   Ant 通配符速查：
                //     ?  —— 匹配任意 1 个字符
                //     *  —— 匹配任意 1 层路径中的字符
                //     ** —— 匹配任意多层路径中的字符
                //
                //   如果不写 addPathPatterns，默认也是拦截 "/**"，所以这里其实可以省略
                //   但写出来更清晰，明确表达"我就是要拦所有"
                //
                //   对比 excludePathPatterns：
                //     addPathPatterns("/**").excludePathPatterns("/api/**")
                //     意思是：拦截所有 / 但排除 /api/**

                .order(0);
        // ↑ order() —— 配置拦截器的【执行顺序】
        //   数字越小，优先级越高，preHandle 越先执行
        //   afterCompletion 则相反：数字越小越后执行
        //
        //   为什么 RefreshTokenInterceptor 要设 order(0) 优先执行？
        //   ⭐ 因为它要【先查 Redis、先存 ThreadLocal】！
        //      LoginInterceptor 要从 ThreadLocal 拿数据，如果 RefreshTokenInterceptor
        //      还没来得及存，ThreadLocal 就是空的，LoginInterceptor 就会误判为"未登录"
        //
        //   执行顺序对比：
        //     order(0) 的 RefreshTokenInterceptor.preHandle → 先查 Redis、存 ThreadLocal
        //     order(1) 的 LoginInterceptor.preHandle        → 后查 ThreadLocal、做拦截决策 ✅
        //
        //   如果反过来设 order(1) 和 order(0)：
        //     LoginInterceptor 先执行 → ThreadLocal 为空 → 所有请求都被 401 拦了！❌


        // ==================== 第二层：LoginInterceptor ====================
        // 核心职责：强制登录校验
        // 特点：只拦截需要登录的路径，ThreadLocal 没用户就 401

        registry.addInterceptor(new LoginInterceptor())
                // ↑ new LoginInterceptor() —— 不传任何参数
                //   为什么？因为 LoginInterceptor 只调 UserHolder.getUser()（静态方法）
                //   不需要查 Redis，所以不需要 StringRedisTemplate
                //   内部没有任何需要注入的 Bean

                .excludePathPatterns(
                        "/shop/**",       // 店铺浏览列表/详情 —— 游客也能看
                        "/voucher/**",    // 优惠券浏览 —— 首页要展示，游客可看
                        "/shop-type/**",  // 店铺类型下拉 —— 首页要加载，游客可看
                        "/upload/**",     // 上传相关 —— 开发测试用，放行方便
                        "/blog/hot",      // 热门博客列表 —— 首页展示，游客可浏览
                        "/user/code",     // 发送验证码 —— 登录前必须能访问！否则卡死
                        "/user/login"     // 登录接口本身 —— 登录前必须能访问！否则卡死
                )
                // ↑ excludePathPatterns() —— 配置【排除路径】，即这些路径不拦截（放行）
                //   没被 exclude 的路径 → 都会被拦截 → 需要登录
                //   比如 /user/me、/blog/like、/voucher/order 等都需要登录才能访问
                //
                //   为什么登录相关的两个路径要放行？
                //   如果 /user/login 也被 LoginInterceptor 拦截：
                //   用户想登录 → 请求 /user/login → 被拦截 → 返回 401
                //   用户永远无法登录！这是死锁！
                //   所以 /user/login 和 /user/code 必须放行

                .order(1);
        // ↑ order(1) —— 比 RefreshTokenInterceptor 的 order(0) 低优先级
        //   preHandle 在 RefreshTokenInterceptor 之后执行
        //   afterCompletion 在 RefreshTokenInterceptor 之前执行
    }
}