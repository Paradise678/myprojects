package com.shophub.pay.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信支付渠道骨架 —— 接微信 JSAPI/Native 时在此实现
 *
 * <p>接入步骤：</p>
 * <ol>
 *   <li>pom 加 com.wechat.pay:java-wechatpay SDK</li>
 *   <li>application.yaml 配 wechat.pay.* 商户参数</li>
 *   <li>{@link #generatePayUrl} 内调 WechatPayClient 下单拿到 codeUrl</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "shop.pay.channel.wechat.enabled", havingValue = "true", matchIfMissing = false)
public class WechatPayChannel implements PayChannel {

    @Override
    public String getChannelCode() {
        return "WECHAT";
    }

    @Override
    public String generatePayUrl(String payNo, int amount) {
        // TODO: 接入微信支付 SDK 后替换
        return "weixin://wxpay/bizpayurl?pr=" + payNo;
    }
}
