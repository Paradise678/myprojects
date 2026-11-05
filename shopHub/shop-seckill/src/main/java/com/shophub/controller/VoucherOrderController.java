package com.shophub.controller;


import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shophub.annotation.RateLimit;
import com.shophub.dto.Result;
import com.shophub.dto.PayResultDTO;
import com.shophub.entity.VoucherOrder;
import com.shophub.service.IVoucherOrderService;
import com.shophub.service.IPayService;
import com.shophub.service.ISeckillVoucherService;
import com.shophub.utils.RedisConstants;
import com.shophub.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * ShopHub・店汇通 秒杀订单控制器
 */
@RestController
@RequestMapping("/voucher-order")
@Tag(name = "秒杀订单", description = "ShopHub・店汇通 秒杀核心：Lua 原子预扣库存 + 一人一单 + RocketMQ 事务消息削峰。包含秒杀下单、核销、取消、库存预热。")
public class VoucherOrderController {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderController.class);

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @DubboReference(version = "1.0.0", check = false)
    private IPayService payService;

    /**
     * 秒杀下单。
     * Redis Lua 原子预扣库存 + 一人一单校验，通过后发 RocketMQ 事务消息异步创建订单和支付单。
     * 需要登录（authorization Header）。
     */
    @RateLimit(windowSeconds = 1, limit = 10, perUser = true)
    @Operation(summary = "秒杀下单", description = "秒杀核心接口：Redis Lua 原子预扣库存 + 一人一单 Set 去重 + RocketMQ 事务消息异步削峰。需要登录（authorization Header）。返回秒杀订单ID（雪花ID）。失败返回 \"库存不足\" / \"不能重复下单\"。")
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@Parameter(description = "秒杀券ID，如 10") @PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 核销：已支付订单 → 已核销 (status 2→3)
     *
     * <p>实际场景需要校验核销人是否是商家/店员，这里简化为登录用户即可核销。</p>
     */
    @Operation(summary = "核销优惠券", description = "已支付订单核销，订单状态 2(已支付) → 3(已核销)。乐观锁保护，防止并发核销。需要登录且只能核销自己的订单。")
    @PostMapping("use/{orderId}")
    public Result useVoucher(@Parameter(description = "秒杀订单ID") @PathVariable Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权核销该订单");
        }
        if (order.getStatus() != 2) {
            return Result.fail("订单当前状态无法核销: status=" + order.getStatus());
        }

        boolean updated = voucherOrderService.update(
                new LambdaUpdateWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getId, orderId)
                        .eq(VoucherOrder::getStatus, 2)   // 乐观锁
                        .set(VoucherOrder::getStatus, 3)
                        .set(VoucherOrder::getUseTime, LocalDateTime.now())
                        .set(VoucherOrder::getUpdateTime, LocalDateTime.now())
        );
        if (!updated) {
            return Result.fail("核销失败，订单状态已变更");
        }
        return Result.ok("核销成功");
    }

    /**
     * 用户主动取消秒杀订单（只限未支付 status=1）。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>调 shop-pay Dubbo cancelPay 关闭 PENDING 支付单（→ FAILED）</li>
     *   <li>乐观锁更新 VoucherOrder 1→4</li>
     *   <li>Redis 库存回补 incrby +1 + srem userId</li>
     * </ol>
     *
     * <p>注：shop-pay cancelPay 也会通过 Outbox 发 PAY_FAIL MQ，
     * PayResultConsumer 消费后也会触发库存回补 —— 这里直接做一次，
     * 消费端那边做幂等保护（updated=0 跳过），不冲突。</p>
     */
    @Operation(summary = "取消秒杀订单", description = "用户主动取消未支付(status=1)的秒杀订单。自动 Dubbo 调 shop-pay 关闭 PENDING 支付单 → FAILED；乐观锁更新订单 1→4(已取消)；DB+Redis 双重库存回补。需要登录且只能取消自己的订单。")
    @PostMapping("cancel/{orderId}")
    public Result cancelOrder(@Parameter(description = "秒杀订单ID") @PathVariable Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权取消该订单");
        }
        if (order.getStatus() != 1) {
            return Result.fail("订单当前状态不允许取消: status=" + order.getStatus());
        }

        // 1. Dubbo 先查出 payNo → 再关支付单
        try {
            PayResultDTO pay = payService.queryPayByOrderNo(String.valueOf(orderId));
            if (pay == null || pay.getPayNo() == null) {
                return Result.fail("未找到关联支付单");
            }
            payService.cancelPay(pay.getPayNo(), "USER_CANCEL");
        } catch (Exception e) {
            return Result.fail("关闭支付单失败: " + e.getMessage());
        }

        // 2. 订单状态 1→4
        boolean updated = voucherOrderService.update(
                new LambdaUpdateWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getId, orderId)
                        .eq(VoucherOrder::getStatus, 1)
                        .set(VoucherOrder::getStatus, 4)
                        .set(VoucherOrder::getUpdateTime, LocalDateTime.now())
        );

        // 3. 库存回补（即使 updated=false 也尝试——可能 Consumer 已处理过）
        restoreStock(order);

        if (!updated) {
            return Result.ok("订单已被取消（消费端已同步处理）");
        }
        return Result.ok("取消成功");
    }

    /** 库存回补：Redis incrby stock +1 / srem userId + DB stock +1 */
    private void restoreStock(VoucherOrder order) {
        if (order.getVoucherId() == null) return;
        Long voucherId = order.getVoucherId();
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;

        // Redis 回补
        try {
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(orderKey, String.valueOf(order.getUserId()));
        } catch (Exception e) {
            log.error("[cancel] Redis 回补失败 voucherId={}: {}", voucherId, e.getMessage());
        }

        // DB 回补
        try {
            seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucherId)
                    .update();
        } catch (Exception e) {
            log.error("[cancel] DB 回补失败 voucherId={}: {}", voucherId, e.getMessage());
        }
    }

    /** 手动预热秒杀券库存到 Redis（调试用，生产用定时任务） */
    @Operation(summary = "预热秒杀库存到 Redis", description = "把所有秒杀券的 DB 库存加载到 Redis（覆盖写），同时清掉每人一单的 Redis Set —— 相当于重置 Redis 秒杀状态。每次跑全链路前先调一次这个接口。")
    @PostMapping("/warmup")
    public Result warmup() {
        var vouchers = seckillVoucherService.list();
        int resetCount = 0;
        for (var v : vouchers) {
            // 覆盖写：确保 Redis 库存 = DB 库存
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + v.getVoucherId();
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(v.getStock()));
            // 清一人一单 Set —— 允许同一用户重复秒杀（测试场景需要）
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + v.getVoucherId();
            Boolean deleted = stringRedisTemplate.delete(orderKey);
            if (Boolean.TRUE.equals(deleted)) resetCount++;
        }
        return Result.ok("预热完成: 共 " + vouchers.size() + " 张券，重置了 " + resetCount + " 个用户集合");
    }
}
