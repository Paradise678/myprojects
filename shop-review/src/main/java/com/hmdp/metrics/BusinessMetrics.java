package com.hmdp.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 业务指标统一出口，指标通过 /actuator/prometheus 暴露。
 */
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 秒杀请求结果：accepted / sold_out / duplicate / error
     */
    public void seckillResult(String result) {
        meterRegistry.counter("seckill_order_result", "result", result).increment();
    }

    /**
     * 被限流拦截的请求数，按方法名区分
     */
    public void rateLimited(String method) {
        meterRegistry.counter("rate_limit_blocked_total", "method", method).increment();
    }

    /**
     * 消息消费失败（等待 RocketMQ 重投），按券 ID 区分
     */
    public void consumeFailed(Long voucherId) {
        meterRegistry.counter("seckill_consume_failed_total",
                "voucherId", String.valueOf(voucherId)).increment();
    }

    /**
     * 消息转入死信队列，按券 ID 区分
     */
    public void deadLetter(Long voucherId) {
        meterRegistry.counter("seckill_dead_letter_total",
                "voucherId", String.valueOf(voucherId)).increment();
    }
}
