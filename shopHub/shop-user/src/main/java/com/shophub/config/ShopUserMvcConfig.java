package com.shophub.config;

import com.shophub.utils.LoginInterceptor;
import com.shophub.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * shop-user Spring MVC 配置 —— 注册认证双拦截器。
 *
 * <p>执行顺序：</p>
 * <ol>
 *   <li>RefreshTokenInterceptor (order=0)：查 Redis Token → 存 ThreadLocal → 刷新 TTL</li>
 *   <li>LoginInterceptor (order=1)：从 ThreadLocal 拿用户 → 没用户就 401</li>
 * </ol>
 *
 * <p>shop-user 的接口：</p>
 * <ul>
 *   <li>放行：/user/code、/user/login（登录前必须能访问）</li>
 *   <li>拦截：/user/me（查当前用户信息需要登录）</li>
 * </ul>
 */
@Configuration
public class ShopUserMvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 第一层：刷新 Token（order=0 先执行）
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // 第二层：强制登录（order=1 后执行）
        // ⚠️ 路径变量加正则约束 {id:\\d+} —— 只匹配数字 ID
        // 否则 "/user/{id}" 会把 "/user/me"、"/user/sign" 等也排除掉（Spring MVC 通配符贪婪匹配）
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",             // 发验证码 —— 登录前必须能访问
                        "/user/login",            // 登录接口本身
                        "/user/{id:\\d+}",        // 按数字 ID 查用户（公开）—— 正则限定只匹配数字
                        "/user/info/{id:\\d+}",   // 按数字 ID 查用户详情（公开）
                        "/actuator/**",
                        // ========== Knife4j / springdoc ==========
                        "/doc.html", "/doc.html/**",
                        "/v3/api-docs/**", "/v3/api-docs",
                        "/swagger-ui.html", "/swagger-ui/**",
                        "/webjars/**", "/favicon.ico"
                )
                .order(1);
    }
}
