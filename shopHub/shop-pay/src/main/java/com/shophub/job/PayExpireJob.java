package com.shophub.job;

import com.shophub.entity.PayMessageTemp;
import com.shophub.entity.PayOrder;
import com.shophub.mapper.PayMessageTempMapper;
import com.shophub.mapper.PayOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付单过期自动关闭定时任务
 *
 * <p>状态机闭环：PENDING(0) 支付单超过 expire_time 仍未被渠道回调，
 * 本任务将其推进到 FAILED(3)，并通过 Outbox 发出 PAY_FAIL 消息，
 * 下游 PayResultConsumer 据此关闭关联的秒杀订单。</p>
 *
 * <p>核心机制：</p>
 * <ol>
 *   <li>乐观锁 UPDATE status=3 WHERE pay_no=? AND status=0 —— 防并发冲突（渠道刚好回调）</li>
 *   <li>每条过期单独立事务 —— 一条失败不影响整体批处理</li>
 *   <li>LIMIT 200 —— 单次扫描上限，防历史遗留脏数据一次拉爆</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayExpireJob {

    private final PayOrderMapper payOrderMapper;
    private final PayMessageTempMapper messageMapper;

    private static final String PAY_TOPIC = "pay-topic";

    /** 每分钟扫一次过期支付单 */
    @Scheduled(fixedRate = 60000, initialDelay = 10000)
    public void expirePendingOrders() {
        List<PayOrder> expired = payOrderMapper.selectExpiredOrders();
        if (expired == null || expired.isEmpty()) {
            return;
        }

        log.info("[ExpireJob] 扫描到 {} 条过期 PENDING 支付单", expired.size());
        int closed = 0, skipped = 0;

        for (PayOrder order : expired) {
            try {
                // 1. 乐观锁推进：WHERE status=0 防止刚好有渠道回调并发推进
                int updated = payOrderMapper.markExpired(
                        order.getPayNo(),
                        "EXPIRED: auto-closed by PayExpireJob at " + java.time.LocalDateTime.now());

                if (updated == 0) {
                    // 被并发请求（比如渠道刚好回调）抢先推进了 —— 跳过
                    skipped++;
                    log.debug("[ExpireJob] payNo={} 已被并发推进，跳过", order.getPayNo());
                    continue;
                }

                // 2. Outbox：写一条 PAY_FAIL 消息（Outbox 会负责发 MQ）
                PayMessageTemp msg = new PayMessageTemp();
                msg.setMsgType("PAY_FAIL");
                msg.setBizKey(order.getPayNo());
                msg.setMsgBody(buildExpireMessage(order));
                msg.setMqTopic(PAY_TOPIC);
                msg.setMqTag("pay-expired");
                msg.setStatus(PayMessageTemp.STATUS_PENDING);
                messageMapper.insert(msg);

                closed++;
                log.info("[ExpireJob] 过期关闭 payNo={}, orderNo={}, amount={}",
                        order.getPayNo(), order.getOrderNo(), order.getAmount());

            } catch (Exception e) {
                // 单条失败不影响批量处理
                log.error("[ExpireJob] 处理 payNo={} 异常: {}", order.getPayNo(), e.getMessage(), e);
            }
        }

        if (closed > 0 || skipped > 0) {
            log.info("[ExpireJob] 本轮完成: closed={}, skipped={}", closed, skipped);
        }
    }

    /** 构造过期通知消息体（下游 PayResultConsumer 消费） */
    private String buildExpireMessage(PayOrder order) {
        return "{"
                + "\"payNo\":\"" + order.getPayNo() + "\","
                + "\"orderNo\":\"" + order.getOrderNo() + "\","
                + "\"userId\":" + order.getUserId() + ","
                + "\"amount\":" + order.getAmount() + ","
                + "\"success\":false,"
                + "\"reason\":\"EXPIRED\","
                + "\"expireTime\":\"" + order.getExpireTime() + "\""
                + "}";
    }
}
