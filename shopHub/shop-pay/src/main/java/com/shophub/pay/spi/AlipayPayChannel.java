package com.shophub.pay.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付宝支付渠道骨架 —— 接支付宝当面付/手机网站支付时在此实现
 *
 * <p>接入步骤：</p>
 * <ol>
 *   <li>pom 加 com.alipay.sdk:alipay-sdk-java</li>
 *   <li>application.yaml 配 alipay.* 商户参数</li>
 *   <li>{@link #generatePayUrl} 内调 AlipayClient.pageExecute 拿到表单 HTML/链接</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "shop.pay.channel.alipay.enabled", havingValue = "true", matchIfMissing = false)
public class AlipayPayChannel implements PayChannel {

    @Override
    public String getChannelCode() {
        return "ALIPAY";
    }

    @Override
    public String generatePayUrl(String payNo, int amount) {
        // TODO: 接入支付宝 SDK 后替换
        return "https://openapi.alipay.com/gateway.do?out_trade_no=" + payNo + "&total_amount=" + (amount / 100.0);
    }
}
