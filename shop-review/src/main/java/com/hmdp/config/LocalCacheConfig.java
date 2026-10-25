package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地缓存配置（多级缓存的 L1 层）。
 *
 * <p>缓存层级：Caffeine（进程内）→ Redis（分布式）→ MySQL。</p>
 *
 * <p>为什么需要本地缓存？</p>
 * <ul>
 *   <li>热点 Key（如爆火店铺详情）QPS 极高时，Redis 会成为带宽/CPU 瓶颈；
 *       进程内缓存命中只需纳秒级，不产生网络开销</li>
 *   <li>TTL 只有几秒，多实例间的短暂不一致可接受；
 *       更新店铺时通过删除本地缓存 + Redis 保证最终一致（见 ShopServiceImpl.update）</li>
 * </ul>
 *
 * <p>一致性取舍：多实例部署时，本实例更新无法直接删除其他实例的本地缓存，
 * 其他实例最多脏 5 秒（TTL 上限）。若要求更强一致，可引入 Redis Pub/Sub
 * 广播失效消息，代价是增加复杂度。</p>
 */
@Configuration
public class LocalCacheConfig {

    @Value("${shop.local-cache.max-size:1000}")
    private long maxSize;

    @Value("${shop.local-cache.ttl-seconds:5}")
    private long ttlSeconds;

    /**
     * 店铺本地缓存：基于容量淘汰 + 写后过期。
     *
     * <p>recordStats + CaffeineCacheMetrics：命中率等统计自动暴露到
     * /actuator/prometheus（caffeine_cache_* 指标），便于压测对比。</p>
     */
    @Bean
    public Cache<Long, Shop> shopLocalCache(MeterRegistry meterRegistry) {
        Cache<Long, Shop> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "shopLocalCache");
        return cache;
    }

    /**
     * 延迟双删的延迟删除调度器。
     *
     * <p>单线程即可：删除缓存是轻量操作；daemon 线程不阻碍 JVM 退出。</p>
     */
    @Bean
    public ScheduledExecutorService cacheDeleteScheduler() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "cache-delayed-delete-" + seq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newScheduledThreadPool(1, factory);
    }
}
