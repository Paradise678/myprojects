package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 事务消息监听器。
 *
 * <p>本地事务：扣减 DB 库存 + 写订单。
 * 失败时补偿 Redis：把用户从已下单集合移除，并恢复 Redis 库存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    private final IVoucherOrderService voucherOrderService;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        VoucherOrder order = (VoucherOrder) arg;
        try {
            boolean success = voucherOrderService.saveOrderInTransaction(order);
            if (success) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            // DB 库存扣减失败：回滚 Redis 预扣
            voucherOrderService.rollbackRedisReservation(order);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("秒杀本地事务执行失败 orderId={}", order.getId(), e);
            voucherOrderService.rollbackRedisReservation(order);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * Broker 回查：本地事务结果未知时，以订单是否落库为准。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        try {
            SeckillOrderMessage payload = parsePayload(msg.getPayload());

            if (voucherOrderService.orderExists(payload.getOrderId())) {
                return RocketMQLocalTransactionState.COMMIT;
            }

            VoucherOrder order = new VoucherOrder()
                    .setId(payload.getOrderId())
                    .setUserId(payload.getUserId())
                    .setVoucherId(payload.getVoucherId());
            voucherOrderService.rollbackRedisReservation(order);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("事务消息回查失败", e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 回查消息的 payload 可能是对象、JSON 字符串或 byte[]，这里统一兜底解析。
     */
    private SeckillOrderMessage parsePayload(Object payload) {
        if (payload instanceof SeckillOrderMessage) {
            return (SeckillOrderMessage) payload;
        }
        if (payload instanceof byte[]) {
            return JSONUtil.toBean(new String((byte[]) payload, StandardCharsets.UTF_8),
                    SeckillOrderMessage.class);
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(payload), SeckillOrderMessage.class);
    }
}
