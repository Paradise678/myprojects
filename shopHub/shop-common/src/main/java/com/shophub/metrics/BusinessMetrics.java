package com.shophub.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    public BusinessMetrics(ObjectProvider<MeterRegistry> provider) {
        this.meterRegistry = provider.getObject();
    }

    public void seckillResult(String result) {
        meterRegistry.counter("seckill_order_result", "result", result).increment();
    }

    public void rateLimited(String method) {
        meterRegistry.counter("rate_limit_blocked_total", "method", method).increment();
    }

    public void consumeFailed(Long voucherId) {
        meterRegistry.counter("seckill_consume_failed_total",
                "voucherId", String.valueOf(voucherId)).increment();
    }

    public void deadLetter(Long voucherId) {
        meterRegistry.counter("seckill_dead_letter_total",
                "voucherId", String.valueOf(voucherId)).increment();
    }
}
