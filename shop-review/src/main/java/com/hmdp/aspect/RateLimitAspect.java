package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.RateLimitException;
import com.hmdp.metrics.BusinessMetrics;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;

/**
 * 滑动窗口限流切面。
 *
 * <p>执行顺序设置为最高优先级，确保在事务、业务逻辑之前就完成限流判断。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessMetrics businessMetrics;

    @Value("${rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 限流总开关关闭时直接放行
        if (!rateLimitEnabled) {
            return joinPoint.proceed();
        }

        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();

        String identity;
        if (rateLimit.perUser()) {
            UserDTO user = UserHolder.getUser();
            // 已登录按用户维度，未登录降级为 IP 维度
            identity = user != null ? "u" + user.getId() : "ip" + getClientIp();
        } else {
            identity = "ip" + getClientIp();
        }

        String key = "rl:" + method + ":" + identity;
        long now = System.currentTimeMillis();
        String member = now + ":" + UUID.randomUUID();

        Long allowed;
        try {
            allowed = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(rateLimit.windowSeconds() * 1000L),
                    String.valueOf(rateLimit.limit()),
                    member
            );
        } catch (Exception e) {
            // Redis 异常时放行，避免限流组件拖垮业务。
            // 若业务要求严格限流，可改为抛异常拒绝请求。
            log.warn("限流脚本执行失败，本次请求放行 key={}", key, e);
            return joinPoint.proceed();
        }

        if (allowed == null || allowed == 0L) {
            businessMetrics.rateLimited(method);
            throw new RateLimitException("请求过于频繁，请稍后再试");
        }

        return joinPoint.proceed();
    }

    /**
     * 获取真实客户端 IP：优先 X-Forwarded-For，其次 X-Real-IP，最后 remoteAddr。
     */
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwardedFor) && !"unknown".equalsIgnoreCase(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
