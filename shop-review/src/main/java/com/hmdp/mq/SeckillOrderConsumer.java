package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.SeckillMessageLog;
import com.hmdp.mapper.SeckillMessageLogMapper;
import com.hmdp.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 秒杀订单消息消费者：并行消费 + 重试上限 + 死信队列。
 *
 * <p>并行化设计：</p>
 * <ul>
 *   <li>单实例并行度由配置注入（{@code seckill.consumer.thread-min/thread-max}），
 *       通过 {@link RocketMQPushConsumerLifecycleListener} 在消费者启动前设置</li>
 *   <li>集群模式（CLUSTERING）：多实例部署同一 consumerGroup 时，
 *       Broker 自动把队列分摊给各实例，吞吐随实例数横向扩展</li>
 *   <li>CONCURRENTLY 并发消费：下单消息之间无顺序依赖，追求吞吐</li>
 * </ul>
 *
 * <p>重试与死信设计：</p>
 * <ul>
 *   <li>业务失败抛出异常，RocketMQ 按退避间隔自动重投（10s/30s/1m/2m...）</li>
 *   <li>{@code reconsumeTimes} 达到 {@code seckill.consumer.max-retry} 后：
 *       先同步投递到死信 Topic（seckill-order-dlq-topic），
 *       投递成功才把消费日志标记为死信并 ACK；
 *       死信投递本身失败则继续走正常重试，保证消息至少一次进入死信，不丢</li>
 *   <li>消费日志 status：0 处理中 / 1 已消费 / 2 已入死信</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = SeckillOrderProducer.TOPIC,
        consumerGroup = "seckill-order-consumer-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class SeckillOrderConsumer implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    /**
     * 死信 Topic：超过最大重试次数的消息转存到这里，供人工排查或补偿任务处理
     */
    public static final String DLQ_TOPIC = "seckill-order-dlq-topic";

    /**
     * 消费日志状态
     */
    private static final int STATUS_PROCESSING = 0;
    private static final int STATUS_CONSUMED = 1;
    private static final int STATUS_DEAD = 2;

    private final SeckillMessageLogMapper messageLogMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;

    /**
     * 单实例消费线程数下限
     */
    @Value("${seckill.consumer.thread-min:10}")
    private int threadMin;

    /**
     * 单实例消费线程数上限
     */
    @Value("${seckill.consumer.thread-max:20}")
    private int threadMax;

    /**
     * 业务失败最大重试次数，超过后转入死信
     */
    @Value("${seckill.consumer.max-retry:3}")
    private int maxRetry;

    /**
     * 置为 true 可模拟下游业务失败，用于验证重试与死信链路
     */
    @Value("${seckill.consumer.simulate-fail:false}")
    private boolean simulateFail;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // 并行度来自配置文件，多实例部署时每个实例按同样参数生效
        consumer.setConsumeThreadMin(threadMin);
        consumer.setConsumeThreadMax(threadMax);
        log.info("秒杀消费者启动 consumerGroup={}, threadMin={}, threadMax={}, maxRetry={}",
                consumer.getConsumerGroup(), threadMin, threadMax, maxRetry);
    }

    @Override
    public void onMessage(MessageExt message) {
        SeckillOrderMessage payload = JSONUtil.toBean(
                new String(message.getBody(), StandardCharsets.UTF_8), SeckillOrderMessage.class);

        Long orderId = payload.getOrderId();
        int reconsumeTimes = message.getReconsumeTimes();

        // 1. 幂等检查：status=1 说明下游业务已完成，直接 ACK
        SeckillMessageLog existing = messageLogMapper.selectOne(
                new LambdaQueryWrapper<SeckillMessageLog>()
                        .eq(SeckillMessageLog::getOrderId, orderId));

        if (existing != null && STATUS_CONSUMED == existing.getStatus()) {
            log.info("订单消息已消费，跳过 orderId={}", orderId);
            return;
        }

        // 2. 抢占处理权：唯一索引 uk_order_id，并发消费者只有先插入的继续处理
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            SeckillMessageLog logEntity = new SeckillMessageLog()
                    .setOrderId(orderId)
                    .setUserId(payload.getUserId())
                    .setVoucherId(payload.getVoucherId())
                    .setStatus(STATUS_PROCESSING)
                    .setRetryCount(reconsumeTimes)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            try {
                messageLogMapper.insert(logEntity);
            } catch (DuplicateKeyException e) {
                log.info("并发重复消息，跳过 orderId={}", orderId);
                return;
            }
        }

        // 3. 执行下游业务；失败按重试次数决定重投或转死信
        try {
            doBusiness(payload);
            updateLog(orderId, STATUS_CONSUMED, null, reconsumeTimes);
        } catch (Exception e) {
            handleConsumeFailure(payload, message, reconsumeTimes, e);
        }
    }

    /**
     * 下游业务扩展点：发送下单通知、增加积分、写入统计等。
     */
    private void doBusiness(SeckillOrderMessage message) {
        if (simulateFail) {
            throw new IllegalStateException("模拟下游业务失败（seckill.consumer.simulate-fail=true）");
        }
        log.info("秒杀订单消息消费成功 orderId={}, userId={}, voucherId={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId());
    }

    /**
     * 失败处理：未达上限则抛异常触发重投；达到上限先投死信再标记，保证不丢消息。
     */
    private void handleConsumeFailure(SeckillOrderMessage payload, MessageExt message,
                                      int reconsumeTimes, Exception cause) {
        Long orderId = payload.getOrderId();

        if (reconsumeTimes < maxRetry) {
            businessMetrics.consumeFailed(payload.getVoucherId());
            updateLog(orderId, STATUS_PROCESSING, cause.getMessage(), reconsumeTimes + 1);
            log.warn("消费失败，等待 RocketMQ 重投 orderId={}, 第 {}/{} 次",
                    orderId, reconsumeTimes + 1, maxRetry, cause);
            // 抛出异常 => Broker 按退避间隔重投
            throw new RuntimeException(cause);
        }

        // 达到重试上限：先投死信，投递失败则继续走正常重试（不丢消息）
        try {
            sendToDlq(payload, message, cause);
            updateLog(orderId, STATUS_DEAD, cause.getMessage(), reconsumeTimes);
            businessMetrics.deadLetter(payload.getVoucherId());
            log.error("消费失败达到重试上限，已转入死信 orderId={}, reconsumeTimes={}, 原因: {}",
                    orderId, reconsumeTimes, cause.getMessage());
        } catch (Exception dlqError) {
            log.error("死信投递失败，继续按普通失败重试 orderId={}", orderId, dlqError);
            throw new RuntimeException(dlqError);
        }
    }

    /**
     * 同步投递死信：携带原始消息 JSON 与失败原因，供补偿任务或人工重放。
     */
    private void sendToDlq(SeckillOrderMessage payload, MessageExt message, Exception cause) {
        Message<String> dlqMessage = MessageBuilder
                .withPayload(new String(message.getBody(), StandardCharsets.UTF_8))
                .setHeader("orderId", String.valueOf(payload.getOrderId()))
                .setHeader("voucherId", String.valueOf(payload.getVoucherId()))
                .setHeader("originMsgId", message.getMsgId())
                .setHeader("errorMsg", cause.getMessage() == null ? "unknown" : cause.getMessage())
                .build();
        rocketMQTemplate.syncSend(DLQ_TOPIC, dlqMessage);
    }

    private void updateLog(Long orderId, int status, String errorMsg, int retryCount) {
        LambdaUpdateWrapper<SeckillMessageLog> wrapper = new LambdaUpdateWrapper<SeckillMessageLog>()
                .eq(SeckillMessageLog::getOrderId, orderId)
                .set(SeckillMessageLog::getStatus, status)
                .set(SeckillMessageLog::getRetryCount, retryCount)
                .set(SeckillMessageLog::getUpdateTime, LocalDateTime.now());
        if (errorMsg != null) {
            wrapper.set(SeckillMessageLog::getErrorMsg,
                    errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg);
        }
        messageLogMapper.update(null, wrapper);
    }
}
