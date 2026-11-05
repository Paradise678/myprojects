package com.shophub.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shophub.entity.Shop;
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

@Configuration
public class LocalCacheConfig {

    @Value("${shop.local-cache.max-size:1000}")
    private long maxSize;

    @Value("${shop.local-cache.ttl-seconds:5}")
    private long ttlSeconds;

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

    @Bean
    public ScheduledExecutorService cacheDeleteScheduler() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "cache-delayed-delete-" + seq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newScheduledThreadPool(1, threadFactory);
    }
}
