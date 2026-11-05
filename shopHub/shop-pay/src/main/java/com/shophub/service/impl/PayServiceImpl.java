package com.shophub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shophub.dto.PayCreateDTO;
import com.shophub.dto.PayResultDTO;
import com.shophub.entity.PayMessageTemp;
import com.shophub.entity.PayOrder;
import com.shophub.mapper.PayMessageTempMapper;
import com.shophub.mapper.PayOrderMapper;
import com.shophub.pay.spi.PayChannel;
import com.shophub.service.IPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付微服务核心实现
 *
 * 架构要点：
 *   1. 两阶段状态机：createPay 只创建 PENDING；callbackPay 驱动 PAID/FAILED
 *   2. 幂等回调：updateStatusIdempotent 用乐观锁 (WHERE status = fromStatus)
 *      - 返回 0 表示状态已被推进过 → 直接查询当前状态返回
 *      - 返回 1 表示本次回调生效
 *   3. Outbox 模式：在同一个本地事务里写 tb_pay_order + tb_pay_message_temp
 *      - 定时扫描 tb_pay_message_temp 发送 RocketMQ
 *      - 确保 DB 状态与 MQ 通知最终一致
 *   4. 禁止模拟成功兜底：SIMULATE 渠道也走完整两阶段，不自动推进
 */
@Slf4j
@Service
@DubboService(version = "1.0.0")
@RequiredArgsConstructor
public class PayServiceImpl implements IPayService {

    private final PayOrderMapper payOrderMapper;
    private final PayMessageTempMapper messageTempMapper;
    private final java.util.List<PayChannel> payChannels;
    private final ObjectMapper objectMapper;

    private static final String PAY_TOPIC = "pay-topic";

    /** 简单雪花 ID 生成：时间戳 + 随机后缀（不依赖 hutool） */
    private static String nextPayId() {
        long ts = System.currentTimeMillis();
        long rand = ThreadLocalRandom.current().nextLong(1000, 9999);
        return String.valueOf(ts) + rand;
    }

    /** JDK 自带 SHA-256，不依赖 hutool */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    // ==================== 阶段1：创建支付单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultDTO createPay(PayCreateDTO dto) {
        log.info("[Pay] createPay orderNo={}, userId={}, amount={}, channel={}",
                dto.getOrderNo(), dto.getUserId(), dto.getAmount(), dto.getChannel());

        // 1. 幂等：同一订单号只能创建一个支付单
        PayOrder existing = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getOrderNo, dto.getOrderNo()));
        if (existing != null) {
            log.warn("[Pay] 订单已存在支付单 orderNo={}, payNo={}, status={}",
                    dto.getOrderNo(), existing.getPayNo(), existing.getStatus());
            return buildResult(existing);
        }

        // 2. 生成支付单号 + 过期时间
        String payNo = "PAY" + nextPayId();
        // 过期时间兜底：[300s, 86400s] —— 最少5分钟，最多24小时
        int expireSeconds = dto.getExpireSeconds() == null ? 1800
                : Math.max(300, Math.min(dto.getExpireSeconds(), 86400));

        // 3. 创建支付单 —— 只到 PENDING，不自动推进
        PayOrder order = new PayOrder();
        order.setOrderNo(dto.getOrderNo());
        order.setPayNo(payNo);
        order.setUserId(dto.getUserId());
        order.setAmount(dto.getAmount());
        order.setChannel(dto.getChannel() == null ? "SIMULATE" : dto.getChannel());
        order.setStatus(PayOrder.STATUS_PENDING);
        order.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        payOrderMapper.insert(order);

        // 4. 生成支付链接 (mock)
        String payUrl = generatePayUrl(order);

        log.info("[Pay] 支付单创建成功 payNo={}, payUrl={}", payNo, payUrl);
        return PayResultDTO.pending(payNo, dto.getOrderNo(), payUrl, order.getExpireTime());
    }

    // ==================== 阶段2：渠道回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultDTO callbackPay(String payNo, String callbackData, boolean success) {
        log.info("[Pay] callbackPay payNo={}, success={}", payNo, success);

        // 1. 查支付单
        PayOrder order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
        if (order == null) {
            log.error("[Pay] 支付单不存在 payNo={}", payNo);
            throw new IllegalArgumentException("支付单不存在: " + payNo);
        }

        // 3. 已是终态 → 直接返回 (回调已处理过，幂等命中)
        if (order.getStatus() == PayOrder.STATUS_PAID
                || order.getStatus() == PayOrder.STATUS_FAILED
                || order.getStatus() == PayOrder.STATUS_REFUNDED) {
            log.info("[Pay] 支付单已是终态 payNo={}, status={}, 幂等返回", payNo, order.getStatus());
            return buildResult(order);
        }

        // 4. callbackHash 幂等：永远把 payNo 拼进 hash 输入，保证每个支付单唯一
        //    格式：sha256(payNo + "|" + success + "|" + callbackData_or_empty)
        String rawHashInput = payNo + "|" + success
                + "|" + (callbackData == null ? "" : callbackData);
        String callbackHash = sha256Hex(rawHashInput);
        if (order.getCallbackHash() != null && callbackHash.equals(order.getCallbackHash())) {
            log.info("[Pay] callbackHash 命中同报文 payNo={}, hash={}, 跳过", payNo, callbackHash);
            return buildResult(order);
        }

        // 5. 乐观锁 + callbackHash 去重 双保险推进状态机
        int fromStatus = order.getStatus(); // PENDING 或 PAYING
        int toStatus = success ? PayOrder.STATUS_PAID : PayOrder.STATUS_FAILED;

        int updated = payOrderMapper.updateCallbackIdempotent(
                payNo, fromStatus, toStatus, callbackData, callbackHash,
                success ? LocalDateTime.now() : null);

        if (updated == 0) {
            // 两种可能：
            //   a) 状态已被并发请求推进（fromStatus 不匹配）
            //   b) 同一 callbackHash 已被处理过（双保险命中）
            log.warn("[Pay] callback 乐观锁冲突 payNo={}, fromStatus={}, 重新查询", payNo, fromStatus);
            PayOrder latest = payOrderMapper.selectOne(
                    new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
            return buildResult(latest);
        }

        // 6. Outbox：本地事务内写消息表
        PayMessageTemp msg = new PayMessageTemp();
        msg.setMsgType(success ? "PAY_SUCCESS" : "PAY_FAIL");
        msg.setBizKey(payNo);
        msg.setMsgBody(buildMessageBody(order, success));
        msg.setMqTopic(PAY_TOPIC);
        msg.setMqTag(success ? "pay-success" : "pay-fail");
        msg.setStatus(PayMessageTemp.STATUS_PENDING);
        messageTempMapper.insert(msg);

        log.info("[Pay] 状态机推进成功 payNo={}, {} -> {}", payNo, fromStatus, toStatus);
        return success
                ? PayResultDTO.success(payNo, order.getOrderNo(), LocalDateTime.now())
                : PayResultDTO.failed(payNo, order.getOrderNo());
    }

    // ==================== 查询 ====================

    @Override
    public PayResultDTO queryPay(String payNo) {
        PayOrder order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
        if (order == null) {
            throw new IllegalArgumentException("支付单不存在: " + payNo);
        }
        return buildResult(order);
    }

    @Override
    public PayResultDTO queryPayByOrderNo(String orderNo) {
        PayOrder order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new IllegalArgumentException("支付单不存在 for orderNo: " + orderNo);
        }
        return buildResult(order);
    }

    // ==================== 退款 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultDTO refundPay(String payNo, Integer refundAmount, String reason) {
        log.info("[Pay] refundPay payNo={}, refundAmount={}, reason={}", payNo, refundAmount, reason);

        // 1. 查支付单
        PayOrder order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
        if (order == null) {
            throw new IllegalArgumentException("支付单不存在: " + payNo);
        }

        // 2. 已是 REFUNDED → 幂等返回
        if (order.getStatus() == PayOrder.STATUS_REFUNDED) {
            log.info("[Pay] 支付单已退款 payNo={}, 幂等返回", payNo);
            return buildResult(order);
        }

        // 3. 状态保护：只有 PAID 能退款
        if (order.getStatus() != PayOrder.STATUS_PAID) {
            log.warn("[Pay] 退款拒绝 payNo={}, 当前状态={} (非 PAID)", payNo, order.getStatus());
            throw new IllegalStateException("支付单当前状态不允许退款: status=" + order.getStatus());
        }

        // 4. 计算退款金额 + 校验
        int actualRefundAmount = (refundAmount == null) ? order.getAmount() : refundAmount;
        if (actualRefundAmount <= 0 || actualRefundAmount > order.getAmount()) {
            throw new IllegalArgumentException("退款金额非法: " + actualRefundAmount
                    + " (应在 1 ~ " + order.getAmount() + " 之间)");
        }

        // 5. 乐观锁推进状态机 PAID(2) → REFUNDED(4)
        int updated = payOrderMapper.markRefunded(payNo,
                reason == null ? "USER_REQUEST" : reason,
                actualRefundAmount);

        if (updated == 0) {
            // 被并发请求（比如渠道重复退款回调）抢先推进
            log.warn("[Pay] 退款乐观锁冲突 payNo={}, 重新查询", payNo);
            PayOrder latest = payOrderMapper.selectOne(
                    new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
            return buildResult(latest);
        }

        // 6. Outbox：写 REFUND 消息
        PayMessageTemp msg = new PayMessageTemp();
        msg.setMsgType("REFUND");
        msg.setBizKey(payNo);
        msg.setMsgBody(buildRefundMessage(order, actualRefundAmount, reason));
        msg.setMqTopic(PAY_TOPIC);
        msg.setMqTag("pay-refund");
        msg.setStatus(PayMessageTemp.STATUS_PENDING);
        messageTempMapper.insert(msg);

        log.info("[Pay] 退款成功 payNo={}, orderNo={}, refundAmount={}",
                payNo, order.getOrderNo(), actualRefundAmount);

        PayResultDTO result = buildResult(order);
        result.setStatus(PayOrder.STATUS_REFUNDED);
        result.setStatusDesc("已退款");
        return result;
    }

    // ==================== 取消 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultDTO cancelPay(String payNo, String reason) {
        log.info("[Pay] cancelPay payNo={}, reason={}", payNo, reason);

        PayOrder order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo));
        if (order == null) {
            throw new IllegalArgumentException("支付单不存在: " + payNo);
        }

        // 幂等：已是终态直接返回
        if (order.getStatus() == PayOrder.STATUS_PAID
                || order.getStatus() == PayOrder.STATUS_FAILED
                || order.getStatus() == PayOrder.STATUS_REFUNDED) {
            log.info("[Pay] 支付单已是终态 payNo={}, status={}, 幂等返回", payNo, order.getStatus());
            return buildResult(order);
        }

        if (order.getStatus() != PayOrder.STATUS_PENDING) {
            throw new IllegalStateException("支付单当前状态不允许取消: status=" + order.getStatus());
        }

        int updated = payOrderMapper.markCancelled(payNo, reason == null ? "USER_CANCEL" : reason);
        if (updated == 0) {
            log.warn("[Pay] cancelPay 乐观锁冲突 payNo={}, 重新查询", payNo);
            return buildResult(payOrderMapper.selectOne(
                    new LambdaQueryWrapper<PayOrder>().eq(PayOrder::getPayNo, payNo)));
        }

        // Outbox：写 PAY_FAIL（被 Consumer 当作失败处理 → 库存回补）
        PayMessageTemp msg = new PayMessageTemp();
        msg.setMsgType("PAY_FAIL");
        msg.setBizKey(payNo);
        msg.setMsgBody(buildCancelMessage(order, reason));
        msg.setMqTopic(PAY_TOPIC);
        msg.setMqTag("pay-cancel");
        msg.setStatus(PayMessageTemp.STATUS_PENDING);
        messageTempMapper.insert(msg);

        log.info("[Pay] 取消成功 payNo={}, orderNo={}", payNo, order.getOrderNo());
        PayResultDTO result = buildResult(order);
        result.setStatus(PayOrder.STATUS_FAILED);
        result.setStatusDesc("已取消");
        return result;
    }

    /** 构造取消 MQ 消息体 */
    private String buildCancelMessage(PayOrder order, String reason) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("payNo", order.getPayNo());
        map.put("orderNo", order.getOrderNo());
        map.put("userId", order.getUserId());
        map.put("amount", order.getAmount());
        map.put("success", false);
        map.put("reason", reason == null ? "USER_CANCEL" : reason);
        return toJson(map);
    }

    // ==================== 内部工具 ====================

    /** Jackson 序列化，统一处理异常 */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /** 构造支付结果 DTO */
    private PayResultDTO buildResult(PayOrder order) {
        if (order == null) return null;
        PayResultDTO r = new PayResultDTO();
        r.setPayNo(order.getPayNo());
        r.setOrderNo(order.getOrderNo());
        r.setStatus(order.getStatus());
        r.setPayTime(order.getPayTime());
        r.setExpireTime(order.getExpireTime());

        switch (order.getStatus()) {
            case PayOrder.STATUS_PENDING:  r.setStatusDesc("待支付"); break;
            case PayOrder.STATUS_PAYING:   r.setStatusDesc("支付中"); break;
            case PayOrder.STATUS_PAID:     r.setStatusDesc("已支付"); break;
            case PayOrder.STATUS_FAILED:   r.setStatusDesc("支付失败"); break;
            case PayOrder.STATUS_REFUNDED: r.setStatusDesc("已退款"); break;
            default: r.setStatusDesc("未知");
        }
        return r;
    }

    /** SPI 路由：按 channel 字符串找到对应 PayChannel 实现生成支付链接 */
    private String generatePayUrl(PayOrder order) {
        String channel = order.getChannel() == null ? "SIMULATE" : order.getChannel();
        return payChannels.stream()
                .filter(c -> c.getChannelCode().equals(channel))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("[Pay] 未找到渠道实现 channel={}, fallback SIMULATE", channel);
                    return payChannels.stream()
                            .filter(c -> "SIMULATE".equals(c.getChannelCode()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("SIMULATE 渠道未配置"));
                })
                .generatePayUrl(order.getPayNo(), order.getAmount());
    }

    /** 构造 MQ 消息体 JSON */
    private String buildMessageBody(PayOrder order, boolean success) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("msgType", success ? "PAY_SUCCESS" : "PAY_FAIL");
        map.put("payNo", order.getPayNo());
        map.put("orderNo", order.getOrderNo());
        map.put("userId", order.getUserId());
        map.put("amount", order.getAmount());
        map.put("success", success);
        map.put("payTime", LocalDateTime.now().toString());
        return toJson(map);
    }

    /** 构造退款 MQ 消息体 */
    private String buildRefundMessage(PayOrder order, int refundAmount, String reason) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("msgType", "REFUND");
        map.put("payNo", order.getPayNo());
        map.put("orderNo", order.getOrderNo());
        map.put("userId", order.getUserId());
        map.put("originalAmount", order.getAmount());
        map.put("refundAmount", refundAmount);
        map.put("reason", reason == null ? "" : reason);
        map.put("refundTime", LocalDateTime.now().toString());
        return toJson(map);
    }
}
