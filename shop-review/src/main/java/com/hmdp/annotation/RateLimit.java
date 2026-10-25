package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流注解。
 *
 * <p>基于 Redis ZSet + Lua 实现，计数在 Redis 中集中存储，
 * 多实例部署时共享同一份限流窗口，不会因为扩容导致阈值翻倍。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * &#64;RateLimit(windowSeconds = 1, limit = 10)
 * &#64;PostMapping("/seckill/{id}")
 * public Result seckillVoucher(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 窗口大小（秒）
     */
    int windowSeconds() default 1;

    /**
     * 窗口内允许的最大请求数
     */
    int limit() default 100;

    /**
     * 限流维度：
     * true  —— 优先按登录用户限流，未登录时降级为 IP
     * false —— 只按 IP 限流（适用于登录、发验证码等未登录接口）
     */
    boolean perUser() default true;
}
