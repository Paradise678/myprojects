package com.shophub.controller;

import com.shophub.dto.PayCreateDTO;
import com.shophub.dto.PayResultDTO;
import com.shophub.exception.BusinessException;
import com.shophub.service.IPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 支付微服务 HTTP Controller
 */
@Slf4j
@RestController
@RequestMapping("/pay")
@RequiredArgsConstructor
@Tag(name = "支付服务", description = "ShopHub・店汇通 支付微服务：创建支付单、模拟渠道回调、退款、取消、状态查询。支付状态机 PENDING(0) → PAID(2)/FAILED(3) → REFUNDED(4)")
public class PayController {

    private final IPayService payService;

    @Value("${pay.callback.secret:shophub-callback-dev}")
    private String callbackSecret;

    /**
     * 发起支付 —— 由 shop-seckill Dubbo 调用创建支付单
     * 返回 PayResultDTO(status=0, payUrl=模拟支付链接)
     */
    @Operation(summary = "发起支付", description = "创建支付单，状态 PENDING(0)。由秒杀下单 Dubbo 自动调用，也可前端显式调用。返回 payNo 和模拟支付链接。")
    @PostMapping("/create")
    public PayResultDTO create(@RequestBody PayCreateDTO dto) {
        log.info("[HTTP] createPay orderNo={}, userId={}, amount={}", dto.getOrderNo(), dto.getUserId(), dto.getAmount());
        return payService.createPay(dto);
    }

    /**
     * 渠道回调（模拟/真实）
     *
     * 真实场景：微信/支付宝服务器 POST 过来，带签名报文
     * 测试场景：必须带 X-Callback-Secret header，值与配置匹配
     */
    @Operation(summary = "渠道回调（模拟支付成功/失败）", description = "模拟微信/支付宝服务器回调。测试时必须带 Header: X-Callback-Secret = shophub-callback-dev。Body 传 {payNo, success:true/false, channel}。回调成功后 pay_order 状态 PENDING(0) → PAID(2)，通过 Outbox+RocketMQ 通知 shop-seckill。")
    @PostMapping("/callback")
    public PayResultDTO callback(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String providedSecret = request.getHeader("X-Callback-Secret");
        if (!callbackSecret.equals(providedSecret)) {
            log.warn("[HTTP] callback secret 不匹配，拒绝 payNo={}", body.get("payNo"));
            throw new BusinessException("Unauthorized callback", 401);
        }

        String payNo = (String) body.get("payNo");
        String callbackData = (String) body.getOrDefault("callbackData", "");
        boolean success = Boolean.TRUE.equals(body.get("success"));

        log.info("[HTTP] callback payNo={}, success={}", payNo, success);
        return payService.callbackPay(payNo, callbackData, success);
    }

    /**
     * 查询支付单状态
     */
    @Operation(summary = "按支付单号查询", description = "通过 payNo 查询支付单详情和当前状态")
    @GetMapping("/query/{payNo}")
    public PayResultDTO query(@Parameter(description = "支付单号，如 PAY17935152164742821") @PathVariable String payNo) {
        return payService.queryPay(payNo);
    }

    /**
     * 按业务订单号查询
     */
    @Operation(summary = "按业务订单号查询", description = "通过业务订单号（秒杀订单 ID）查询关联的支付单")
    @GetMapping("/query/order/{orderNo}")
    public PayResultDTO queryByOrder(@Parameter(description = "业务订单号/秒杀订单 ID") @PathVariable String orderNo) {
        return payService.queryPayByOrderNo(orderNo);
    }

    /**
     * 退款：将已支付的支付单退回 REFUNDED 状态
     */
    @Operation(summary = "退款", description = "对已支付(PAID)的支付单发起退款，状态 2→4(REFUNDED)。通过 Outbox+RocketMQ 通知 shop-seckill 进行 DB+Redis 双重库存回补。Body: {payNo, refundAmount?(分), reason}")
    @PostMapping("/refund")
    public PayResultDTO refund(@RequestBody Map<String, Object> body) {
        String payNo = (String) body.get("payNo");
        if (payNo == null || payNo.isBlank()) {
            throw new BusinessException("payNo 不能为空", 400);
        }

        Integer refundAmount = null;
        Object amountObj = body.get("refundAmount");
        if (amountObj != null) {
            refundAmount = ((Number) amountObj).intValue();
        }

        String reason = (String) body.get("reason");

        log.info("[HTTP] refund payNo={}, refundAmount={}, reason={}", payNo, refundAmount, reason);
        return payService.refundPay(payNo, refundAmount, reason);
    }

    /**
     * 取消支付单（用户主动取消 PENDING 状态的支付单）。
     * 取消后 payNo 状态变为 FAILED，shop-seckill 侧 PayResultConsumer 会自动：
     *   1. 把 VoucherOrder 状态改为 4(已取消)
     *   2. 回补秒杀 Redis 库存
     */
    @Operation(summary = "取消支付单", description = "用户主动取消 PENDING(0) 状态的支付单，状态变为 FAILED(3)。通过 Outbox 发 PAY_FAIL MQ，shop-seckill 消费后自动关闭秒杀订单并回补库存。Body: {payNo, reason?}")
    @PostMapping("/cancel")
    public PayResultDTO cancel(@RequestBody Map<String, Object> body) {
        String payNo = (String) body.get("payNo");
        if (payNo == null || payNo.isBlank()) {
            throw new BusinessException("payNo 不能为空", 400);
        }
        String reason = (String) body.get("reason");
        log.info("[HTTP] cancel payNo={}, reason={}", payNo, reason);
        return payService.cancelPay(payNo, reason);
    }
}
