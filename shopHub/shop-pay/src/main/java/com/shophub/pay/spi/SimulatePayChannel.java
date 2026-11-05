package com.shophub.pay.spi;

import org.springframework.stereotype.Component;

/**
 * 模拟支付渠道（SIMULATE）—— 用于本地测试 / 秒杀 Demo
 */
@Component
public class SimulatePayChannel implements PayChannel {

    @Override
    public String getChannelCode() {
        return "SIMULATE";
    }

    @Override
    public String generatePayUrl(String payNo, int amount) {
        return "SIMULATE:" + payNo;
    }
}
