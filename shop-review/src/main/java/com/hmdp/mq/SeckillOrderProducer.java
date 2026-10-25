package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 秒杀事务消息生产者。
 *
 * <p>发送半消息后由 {@link SeckillTransactionListener} 执行本地事务（写订单），
 * 只有本地事务提交成功，消费者才能看到消息。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderProducer {

    public static final String TOPIC = "seckill-order-topic";

    private final RocketMQTemplate rocketMQTemplate;

    public void sendOrder(VoucherOrder order) {
        SeckillOrderMessage payload = new SeckillOrderMessage(
                order.getId(), order.getUserId(), order.getVoucherId());

        Message<SeckillOrderMessage> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, String.valueOf(order.getId()))
                .build();

        TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(TOPIC, message, order);

        log.info("秒杀事务消息发送完成 orderId={}, state={}",
                order.getId(), result.getLocalTransactionState());

        if (result.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE) {
            throw new IllegalStateException("秒杀本地事务未提交：" + result.getLocalTransactionState());
        }
    }
}
