package com.shophub.service;

import com.shophub.dto.PayCreateDTO;
import com.shophub.dto.PayResultDTO;

/**
 * 支付微服务 Dubbo Provider 接口
 *
 * 两阶段状态机：
 *   阶段1 (createPay):   创建支付单 → 状态 = PENDING
 *   阶段2 (callbackPay): 渠道回调/确认 → 状态 = PAID / FAILED
 *
 * 不允许在 createPay 阶段直接推进 PAID 状态！
 * 任何终态变更必须由 callbackPay 驱动（回调验签结果为准）。
 */
public interface IPayService {

    /**
     * 阶段1：发起支付 —— 只创建支付单，返回待支付状态
     * @return 包含 payNo + payUrl + expireTime
     */
    PayResultDTO createPay(PayCreateDTO dto);

    /**
     * 阶段2：渠道回调 / 确认支付 —— 验签后驱动状态机到终态
     *
     * 幂等设计：
     *   - 同一个 payNo + callbackData 只成功处理一次
     *   - 已处理过的回调返回上次结果，不重复推进状态
     *
     * @param payNo        支付单号
     * @param callbackData 渠道回调原始报文 (验签 + 幂等校验)
     * @param success      true=支付成功  false=支付失败
     */
    PayResultDTO callbackPay(String payNo, String callbackData, boolean success);

    /**
     * 查询支付单状态
     */
    PayResultDTO queryPay(String payNo);

    /**
     * 根据业务订单号查询支付单状态
     */
    PayResultDTO queryPayByOrderNo(String orderNo);

    /**
     * 退款：将已支付的支付单退回 REFUNDED 状态
     *
     * <p>幂等设计：</p>
     * <ul>
     *   <li>只有 status=PAID 才能退款</li>
     *   <li>已是 REFUNDED → 直接返回当前状态（幂等）</li>
     *   <li>乐观锁防止并发重复退款</li>
     * </ul>
     *
     * @param payNo       支付单号
     * @param refundAmount 退款金额 (分)，null 表示全额退款
     * @param reason      退款原因
     */
    PayResultDTO refundPay(String payNo, Integer refundAmount, String reason);

    /**
     * 取消支付单（用户主动取消 / 业务撤销）。
     *
     * <p>与 refundPay 区别：</p>
     * <ul>
     *   <li>refundPay 针对 PAID(2) 状态（钱已付，退回）</li>
     *   <li>cancelPay 针对 PENDING(0) 状态（钱没付，直接关闭）</li>
     * </ul>
     *
     * <p>幂等设计：</p>
     * <ul>
     *   <li>只有 PENDING 能取消</li>
     *   <li>已是终态(PAID/FAILED/REFUNDED) → 直接返回当前状态</li>
     * </ul>
     *
     * @param payNo 支付单号
     * @param reason 取消原因
     */
    PayResultDTO cancelPay(String payNo, String reason);
}
