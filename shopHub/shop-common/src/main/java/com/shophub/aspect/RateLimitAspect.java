package com.shophub.aspect;

import cn.hutool.core.util.StrUtil;
import com.shophub.annotation.RateLimit;
import com.shophub.dto.UserDTO;
import com.shophub.exception.RateLimitException;
import com.shophub.metrics.BusinessMetrics;
import com.shophub.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Lazy
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessMetrics businessMetrics;

    public RateLimitAspect(ObjectProvider<StringRedisTemplate> redisProvider,
                           ObjectProvider<BusinessMetrics> metricsProvider) {
        this.stringRedisTemplate = redisProvider.getObject();
        this.businessMetrics = metricsProvider.getIfAvailable();
    }

    @Value("${rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!rateLimitEnabled) {
            return joinPoint.proceed();
        }

        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();

        String identity;
        if (rateLimit.perUser()) {
            UserDTO user = UserHolder.getUser();
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
            log.warn("限流脚本执行失败，本次请求放行 key={}", key, e);
            return joinPoint.proceed();
        }

        if (allowed == null || allowed == 0L) {
            if (businessMetrics != null) {
                businessMetrics.rateLimited(method);
            }
            throw new RateLimitException("请求过于频繁，请稍后再试");
        }

        return joinPoint.proceed();
    }

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
