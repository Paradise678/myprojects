package com.shophub.mq;

import cn.hutool.json.JSONUtil;
import com.shophub.entity.VoucherOrder;
import com.shophub.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 事务消息监听器。
 *
 * <p>本地事务：扣减 DB 库存 + 写订单。
 * 失败时补偿 Redis：把用户从已下单集合移除，并恢复 Redis 库存。</p>
 *
 * <p>【回查保护】Broker 回查时如果订单还没落库（DB 事务可能还在跑），
 * 先返回 UNKNOWN 让 Broker 稍后再查，最多重试 5 次，避免过早 ROLLBACK 导致 Redis 库存加多。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final int MAX_CALLBACK_CHECK = 5;
    private static final String CALLBACK_COUNT_KEY = "seckill:tx-callback-count:";

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
     *
     * <p>关键保护：如果订单还没落库（DB 事务可能还在跑），先返回 UNKNOWN，
     * 让 Broker 稍后再查，最多 MAX_CALLBACK_CHECK 次，超过再 ROLLBACK 补偿 Redis。</p>
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        try {
            SeckillOrderMessage payload = parsePayload(msg.getPayload());

            if (voucherOrderService.orderExists(payload.getOrderId())) {
                // 订单已落库 → COMMIT
                // 同时清理回查计数
                stringRedisTemplate.delete(CALLBACK_COUNT_KEY + payload.getOrderId());
                return RocketMQLocalTransactionState.COMMIT;
            }

            // 订单还没落库，看回查次数
            String countKey = CALLBACK_COUNT_KEY + payload.getOrderId();
            String countStr = stringRedisTemplate.opsForValue().get(countKey);
            int count = (countStr == null) ? 0 : Integer.parseInt(countStr);

            if (count >= MAX_CALLBACK_CHECK) {
                // 超过最大回查次数，真正回滚 Redis
                log.warn("事务消息回查 {} 次仍未找到订单，执行补偿 orderId={}", MAX_CALLBACK_CHECK, payload.getOrderId());
                VoucherOrder order = new VoucherOrder()
                        .setId(payload.getOrderId())
                        .setUserId(payload.getUserId())
                        .setVoucherId(payload.getVoucherId());
                voucherOrderService.rollbackRedisReservation(order);
                stringRedisTemplate.delete(countKey);
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // 未超次数，返回 UNKNOWN 让 Broker 稍后再查
            stringRedisTemplate.opsForValue().increment(countKey);
            stringRedisTemplate.expire(countKey, 5, TimeUnit.MINUTES);
            log.info("事务消息回查 orderId={}, 第 {}/{} 次仍未找到订单，返回 UNKNOWN",
                    payload.getOrderId(), count + 1, MAX_CALLBACK_CHECK);
            return RocketMQLocalTransactionState.UNKNOWN;
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
