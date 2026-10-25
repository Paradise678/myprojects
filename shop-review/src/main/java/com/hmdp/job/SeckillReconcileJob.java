package com.hmdp.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.SeckillMessageLog;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillMessageLogMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀对账任务。
 *
 * <p>每 5 分钟对比三份数据：</p>
 * <ul>
 *   <li>Redis 剩余库存 vs DB 剩余库存</li>
 *   <li>Redis 已下单用户数 vs DB 订单数</li>
 *   <li>DB 订单数 vs 消息消费日志数（排除最近 1 分钟，避免正常消费延迟误报）</li>
 * </ul>
 *
 * <p>只做检测和告警，不自动修改数据，避免对账任务本身引入新的不一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReconcileJob {

    private final ISeckillVoucherService seckillVoucherService;
    private final VoucherOrderMapper voucherOrderMapper;
    private final SeckillMessageLogMapper messageLogMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${seckill.reconcile.interval-ms:300000}")
    public void reconcile() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);

        for (SeckillVoucher voucher : vouchers) {
            Long voucherId = voucher.getVoucherId();
            long dbStock = voucher.getStock() == null ? 0 : voucher.getStock();
            long redisStock = parseLong(stringRedisTemplate.opsForValue()
                    .get(RedisConstants.SECKILL_STOCK_KEY + voucherId));
            Long users = stringRedisTemplate.opsForSet()
                    .size(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            long redisUsers = users == null ? 0 : users;

            long dbOrders = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getVoucherId, voucherId));

            long oldOrders = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getVoucherId, voucherId)
                            .lt(VoucherOrder::getCreateTime, oneMinuteAgo));

            long oldLogs = messageLogMapper.selectCount(
                    new LambdaQueryWrapper<SeckillMessageLog>()
                            .eq(SeckillMessageLog::getVoucherId, voucherId)
                            .eq(SeckillMessageLog::getStatus, 1)
                            .lt(SeckillMessageLog::getCreateTime, oneMinuteAgo));

            long stockDiff = Math.abs(redisStock - dbStock);
            long orderDiff = Math.abs(redisUsers - dbOrders);
            long consumeDiff = Math.abs(oldOrders - oldLogs);

            if (stockDiff != 0 || orderDiff != 0 || consumeDiff != 0) {
                log.error("秒杀对账不一致 voucherId={}, redisStock={}, dbStock={}, "
                                + "redisUsers={}, dbOrders={}, oldOrders={}, oldLogs={}",
                        voucherId, redisStock, dbStock, redisUsers, dbOrders, oldOrders, oldLogs);
                meterRegistry.counter("seckill_reconcile_mismatch_total",
                        "voucherId", String.valueOf(voucherId)).increment();
            }
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
