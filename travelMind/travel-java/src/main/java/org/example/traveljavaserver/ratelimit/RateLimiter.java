package org.example.traveljavaserver.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 接口限流器：Redis ZSet + Lua 滑动窗口
 * 多实例部署时共享 Redis 计数，避免单机限流口径不一致。
 *
 * 窗口语义：在任意 windowSeconds 长度的滑动窗口内，同一 Key 最多放行 maxRequests 次请求。
 * Key 规则：rate:travel:{接口}:{客户端IP}
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    /** Lua 脚本：清理过期窗口成员 → 计数 → 未超限则写入新成员并续期，超限返回 0 */
    private static final String LUA =
            "local key = KEYS[1]\n" +
            "local window = tonumber(ARGV[1])\n" +
            "local maxReq = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n" +
            "local count = redis.call('ZCARD', key)\n" +
            "if count < maxReq then\n" +
            "  redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))\n" +
            "  redis.call('PEXPIRE', key, window)\n" +
            "  return 1\n" +
            "end\n" +
            "return 0";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

    @Value("${rate-limit.enabled:true}")
    private boolean enabled;
    @Value("${rate-limit.window-seconds:60}")
    private int windowSeconds;
    @Value("${rate-limit.max-requests:10}")
    private int maxRequests;

    /**
     * 尝试获取一次请求许可
     * @param bizKey 业务标识（如 recommend:127.0.0.1）
     * @return true=放行，false=触发限流
     */
    public boolean tryAcquire(String bizKey) {
        if (!enabled) {
            return true;
        }
        Long result = redisTemplate.execute(script,
                List.of("rate:travel:" + bizKey),
                String.valueOf(windowSeconds * 1000L),
                String.valueOf(maxRequests),
                String.valueOf(System.currentTimeMillis()));
        return result != null && result == 1;
    }
}
