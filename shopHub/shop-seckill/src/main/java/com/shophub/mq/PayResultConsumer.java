package com.shophub.mq;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shophub.entity.VoucherOrder;
import com.shophub.service.ISeckillVoucherService;
import com.shophub.service.IVoucherOrderService;
import com.shophub.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * shop-pay 支付结果消费者 — 驱动秒杀订单完整状态机 + 库存回补
 *
 * <p>消费 pay-topic 三种消息：</p>
 * <pre>
 *   PAY_SUCCESS  → success=true   → VoucherOrder 1 → 2 (已支付)
 *   PAY_FAIL     → success=false  → VoucherOrder 1 → 4 (已取消) + 库存回补
 *   REFUND       → msgType=REFUND → VoucherOrder 2 → 6 (已退款) + 库存回补
 * </pre>
 *
 * <p>幂等设计：</p>
 * <ul>
 *   <li>UPDATE 乐观锁 WHERE status=当前状态 —— 状态已被推进过则 updated=0 直接 ACK</li>
 *   <li>库存回补同样依赖 updated>0 判断 —— 避免重复回补导致库存多 1</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = PayResultConsumer.PAY_TOPIC,
        consumerGroup = "pay-result-consumer-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class PayResultConsumer implements RocketMQListener<String> {

    public static final String PAY_TOPIC = "pay-topic";

    private final IVoucherOrderService voucherOrderService;
    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(String body) {
        JSONObject msg = JSONUtil.parseObj(body);

        String payNo = msg.getStr("payNo");
        String orderNo = msg.getStr("orderNo");
        String msgType = msg.getStr("msgType"); // PAY_SUCCESS / PAY_FAIL / REFUND (shop-pay Outbox 写入)

        log.info("[PayResultConsumer] 收到消息 msgType={}, payNo={}, orderNo={}", msgType, payNo, orderNo);

        // 1. 解析业务订单号 → VoucherOrder.id
        Long orderId;
        try {
            orderId = Long.parseLong(orderNo);
        } catch (NumberFormatException e) {
            log.warn("[PayResultConsumer] orderNo 无法解析为 Long，跳过 payNo={}, orderNo={}", payNo, orderNo);
            return;
        }

        // 2. 先查订单（拿到 voucherId / userId / 当前 status，用于库存回补 + 状态机判断）
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            log.warn("[PayResultConsumer] 订单不存在，跳过 orderId={}, payNo={}", orderId, payNo);
            return;
        }

        // 3. 按 msgType 分支处理
        if ("REFUND".equals(msgType)) {
            handleRefund(order, payNo);
        } else {
            // PAY_SUCCESS / PAY_FAIL —— 老结构用 success 字段
            boolean success = msg.getBool("success");
            if (success) {
                handlePaySuccess(order, payNo);
            } else {
                handlePayFail(order, payNo);
            }
        }
    }

    /** PAY_SUCCESS: 1(未支付) → 2(已支付) */
    private void handlePaySuccess(VoucherOrder order, String payNo) {
        Long orderId = order.getId();
        LambdaUpdateWrapper<VoucherOrder> wrapper = new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 1)
                .set(VoucherOrder::getStatus, 2)
                .set(VoucherOrder::getPayTime, LocalDateTime.now())
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now());

        boolean updated = voucherOrderService.update(wrapper);
        if (!updated) {
            log.info("[PayResultConsumer][SUCCESS] 订单已处理，跳过 orderId={}, payNo={}", orderId, payNo);
            return;
        }
        log.info("[PayResultConsumer][SUCCESS] 订单已支付 orderId={}, payNo={}", orderId, payNo);
    }

    /** PAY_FAIL: 1(未支付) → 4(已取消) + 库存回补 */
    private void handlePayFail(VoucherOrder order, String payNo) {
        Long orderId = order.getId();
        LambdaUpdateWrapper<VoucherOrder> wrapper = new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 1)
                .set(VoucherOrder::getStatus, 4)
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now());

        boolean updated = voucherOrderService.update(wrapper);
        if (!updated) {
            log.info("[PayResultConsumer][FAIL] 订单已处理，跳过 orderId={}, payNo={}", orderId, payNo);
            return;
        }

        // 库存回补（只有 updated>0 才做，防重复回补）
        restoreSeckillStock(order);
        log.info("[PayResultConsumer][FAIL] 订单已取消 + 库存回补 orderId={}, payNo={}", orderId, payNo);
    }

    /** REFUND: 2(已支付)/3(已核销) → 6(已退款) + 库存回补 */
    private void handleRefund(VoucherOrder order, String payNo) {
        Long orderId = order.getId();

        // 幂等：已是 6(已退款) 直接跳过
        if (order.getStatus() == 6) {
            log.info("[PayResultConsumer][REFUND] 订单已退款，幂等跳过 orderId={}, payNo={}", orderId, payNo);
            return;
        }

        LambdaUpdateWrapper<VoucherOrder> wrapper = new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .in(VoucherOrder::getStatus, 2, 3)  // 已支付(2) 或 已核销(3) 都能退款回补
                .set(VoucherOrder::getStatus, 6)
                .set(VoucherOrder::getRefundTime, LocalDateTime.now())
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now());

        boolean updated = voucherOrderService.update(wrapper);
        if (!updated) {
            log.warn("[PayResultConsumer][REFUND] 订单状态非已支付/已核销，跳过 orderId={}, payNo={}, currentStatus={}",
                    orderId, payNo, order.getStatus());
            return;
        }

        // 库存回补
        restoreSeckillStock(order);
        log.info("[PayResultConsumer][REFUND] 订单已退款 + 库存回补 orderId={}, payNo={}", orderId, payNo);
    }

    /**
     * 秒杀库存回补：Redis incrby +1 + set srem userId
     * 对应 Lua seckill_mq.lua 的扣减操作，逆向回补。
     * 支付成功不回补（用户买到了），支付失败/过期/退款才回补。
     */
    private void restoreSeckillStock(VoucherOrder order) {
        log.info("[PayResultConsumer] >>> 进入 restoreSeckillStock orderId={}, voucherId={}, userId={}",
                order.getId(), order.getVoucherId(), order.getUserId());
        if (order.getVoucherId() == null || order.getUserId() == null) {
            log.warn("[PayResultConsumer] 订单缺少 voucherId/userId，跳过库存回补 orderId={}", order.getId());
            return;
        }
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId();
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId();
        Long voucherId = order.getVoucherId();

        // 1. Redis 回补
        try {
            Long newStock = stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(orderKey, String.valueOf(order.getUserId()));
            log.info("[PayResultConsumer] Redis 库存回补 ✅ voucherId={}, userId={}, newStock={}",
                    voucherId, order.getUserId(), newStock);
        } catch (Exception e) {
            log.error("[PayResultConsumer] Redis 库存回补异常 ❌ voucherId={}, orderId={}: {}",
                    voucherId, order.getId(), e.getMessage());
        }

        // 2. DB 库存回补
        try {
            boolean dbRestored = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucherId)
                    .update();
            if (dbRestored) {
                log.info("[PayResultConsumer] DB 库存回补 ✅ voucherId={}", voucherId);
            } else {
                log.warn("[PayResultConsumer] DB 库存回补失败（记录不存在？）voucherId={}", voucherId);
            }
        } catch (Exception e) {
            log.error("[PayResultConsumer] DB 库存回补异常 ❌ voucherId={}, orderId={}: {}",
                    voucherId, order.getId(), e.getMessage());
        }
    }
}
