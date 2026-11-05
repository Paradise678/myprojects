package com.shophub.pay.spi;

/**
 * 支付渠道 SPI 接口 —— 用于路由到不同支付渠道实现
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>每个渠道实现返回对应的 channel 标识（WECHAT / ALIPAY / SIMULATE）</li>
 *   <li>{@link #generatePayUrl(String, int)} 由 Service 根据渠道路由调用</li>
 *   <li>后续接微信/支付宝真 SDK 时，只需新增实现类，不改 Service 主流程</li>
 * </ul>
 */
public interface PayChannel {

    /** 渠道唯一标识，与 tb_pay_order.channel 字段对应 */
    String getChannelCode();

    /**
     * 生成支付链接 / 二维码 / SDK 参数
     *
     * @param payNo  支付单号
     * @param amount 金额 (分)
     * @return 渠道侧的支付拉起字符串
     */
    String generatePayUrl(String payNo, int amount);
}
