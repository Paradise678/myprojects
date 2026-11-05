package com.shophub.job;

import com.shophub.entity.PayMessageTemp;
import com.shophub.mapper.PayMessageTempMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox 本地消息表扫描器
 *
 * 保证 "DB 写操作" 与 "MQ 发送" 的最终一致性：
 *   1. PayServiceImpl 在事务内同时写 tb_pay_order + tb_pay_message_temp
 *   2. 本定时任务每 1 秒扫描待发送/重试的消息
 *   3. 成功发送 RocketMQ 后置 status=1，失败则指数退避重试
 *
 * 指数退避策略：
 *   第 1 次失败 → 10s 后重试
 *   第 2 次失败 → 30s 后重试
 *   第 3 次失败 → 60s 后重试
 *   第 4+ 次   → 120s 后重试
 *   超过 20 次 → 记录 ERROR 日志（人工介入）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageScanner {

    private final PayMessageTempMapper messageMapper;
    private final RocketMQTemplate rocketMQTemplate;

    private static final int MAX_RETRY = 20;

    /** 每秒扫描一次 */
    @Scheduled(fixedRate = 1000, initialDelay = 2000)
    public void scanAndSend() {
        List<PayMessageTemp> messages = messageMapper.selectPendingMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }

        log.debug("[Outbox] 扫描到 {} 条待发送消息", messages.size());

        for (PayMessageTemp msg : messages) {
            try {
                if (msg.getRetryCount() >= MAX_RETRY) {
                    log.error("[Outbox] 消息超过最大重试次数，人工介入 msgId={}, bizKey={}, body={}",
                            msg.getId(), msg.getBizKey(), msg.getMsgBody());
                    // 置为失败终态，避免继续扫描
                    messageMapper.markFailed(msg.getId(), 3600); // 1小时后再试
                    continue;
                }

                // 发送 RocketMQ
                String destination = msg.getMqTopic()
                        + (msg.getMqTag() != null ? ":" + msg.getMqTag() : "");
                rocketMQTemplate.convertAndSend(destination, msg.getMsgBody());

                // 标记成功
                messageMapper.markSent(msg.getId());
                log.info("[Outbox] 消息发送成功 msgId={}, topic={}, tag={}",
                        msg.getId(), msg.getMqTopic(), msg.getMqTag());

            } catch (Exception e) {
                int retryCount = msg.getRetryCount() == null ? 0 : msg.getRetryCount();
                int delaySeconds = calcDelay(retryCount);
                log.warn("[Outbox] 消息发送失败 msgId={}, retryCount={}, nextDelay={}s, error={}",
                        msg.getId(), retryCount, delaySeconds, e.getMessage());

                messageMapper.markFailed(msg.getId(), delaySeconds);
            }
        }
    }

    /** 指数退避延迟 (秒) */
    private int calcDelay(int retryCount) {
        if (retryCount <= 0) return 10;
        if (retryCount == 1) return 30;
        if (retryCount == 2) return 60;
        return 120;
    }
}
