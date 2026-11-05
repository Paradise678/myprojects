package com.shophub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shophub.dto.Result;
import com.shophub.entity.VoucherOrder;
import com.shophub.mapper.VoucherOrderMapper;
import com.shophub.metrics.BusinessMetrics;
import com.shophub.mq.SeckillOrderProducer;
import com.shophub.service.ISeckillVoucherService;
import com.shophub.service.IVoucherOrderService;
import com.shophub.utils.RedisConstants;
import com.shophub.utils.RedisIdWorker;
import com.shophub.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@DubboService(version = "1.0.0")
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BusinessMetrics businessMetrics;

    /** 启动时从 DB 全量预热所有秒杀券库存到 Redis */
    @PostConstruct
    public void warmUpSeckillStock() {
        try {
            var vouchers = seckillVoucherService.list();
            for (var v : vouchers) {
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + v.getVoucherId();
                stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(v.getStock()));
            }
            log.info("[WarmUp] 秒杀券库存预热完成，共 {} 张", vouchers.size());
        } catch (Exception e) {
            log.warn("[WarmUp] 秒杀券库存预热失败（Redis 可能未就绪）: {}", e.getMessage());
        }
    }

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    /**
     * RocketMQ 版预扣脚本：只扣 Redis 库存、记录用户，不再写 Stream。
     */
    private static final DefaultRedisScript<Long> SECKILL_MQ_SCRIPT;

    static {
        SECKILL_MQ_SCRIPT = new DefaultRedisScript<>();
        SECKILL_MQ_SCRIPT.setLocation(new ClassPathResource("lua/seckill_mq.lua"));
        SECKILL_MQ_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. Redis 原子预扣库存 + 一人一单校验
        Long result = stringRedisTemplate.execute(
                SECKILL_MQ_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        int r = result == null ? -1 : result.intValue();
        if (r == 1) {
            businessMetrics.seckillResult("sold_out");
            return Result.fail("库存不足");
        }
        if (r == 2) {
            businessMetrics.seckillResult("duplicate");
            return Result.fail("不能重复下单");
        }
        if (r != 0) {
            businessMetrics.seckillResult("error");
            return Result.fail("秒杀失败，请稍后再试");
        }

        // 2. 发送事务消息：半消息 + 本地事务（扣 DB 库存、写订单）
        VoucherOrder order = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId)
                .setStatus(1)
                .setCreateTime(LocalDateTime.now());

        try {
            seckillOrderProducer.sendOrder(order);
        } catch (Exception e) {
            log.error("发送秒杀事务消息失败 orderId={}", orderId, e);
            // 消息没发出去，必须把 Redis 预扣还回去
            rollbackRedisReservation(order);
            businessMetrics.seckillResult("error");
            return Result.fail("秒杀失败，请稍后再试");
        }

        businessMetrics.seckillResult("accepted");
        return Result.ok(orderId);
    }

    /**
     * 本地事务：扣减 DB 库存 + 写订单。
     *
     * @return true 表示订单已存在或创建成功；false 表示 DB 库存不足
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrderInTransaction(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked;
        try {
            locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取下单锁被中断", e);
        }
        if (!locked) {
            throw new IllegalStateException("获取下单锁失败");
        }

        try {
            // 幂等 1：同一订单号已存在（RocketMQ 重试/回查）
            if (getById(order.getId()) != null) {
                log.info("订单已存在，幂等返回 orderId={}", order.getId());
                return true;
            }

            // 幂等 2：同一用户对同一张券只能下一单（排除已取消/已退款）
            long count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .notIn("status", 4, 6)
                    .count();
            if (count > 0) {
                log.info("用户已下单，幂等返回 userId={}, voucherId={}", userId, voucherId);
                return true;
            }

            // 清理已取消/已退款的旧订单 —— MySQL 唯一索引 uk_user_voucher 不区分 status，
            // 不清理的话即使旧订单是 4/6 状态，新订单 INSERT 也会 DuplicateKeyException
            boolean cleaned = update()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .in("status", 4, 6)
                    .remove();
            if (cleaned) {
                log.info("清理已取消/已退款旧订单 userId={}, voucherId={}", userId, voucherId);
            }

            // DB 库存扣减，借助 stock > 0 条件防止超卖
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                log.warn("DB 库存不足 userId={}, voucherId={}", userId, voucherId);
                return false;
            }

            try {
                save(order);
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底：并发下重复插入视为幂等成功
                log.warn("唯一索引拦截重复订单 orderId={}", order.getId());
            }
            return true;
        } finally {
            // 锁必须在事务提交之后再释放，否则下一个请求可能在订单未提交时读到“不存在”，
            // 从而重复扣减 DB 库存。
            if (lock.isHeldByCurrentThread()) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    lock.unlock();
                                }
                            });
                } else {
                    lock.unlock();
                }
            }
        }
    }

    @Override
    public boolean orderExists(Long orderId) {
        return getById(orderId) != null;
    }

    /**
     * 回滚 Redis 预扣。
     *
     * <p>用 SREM 的返回值判断是否真正移除成功，保证重复补偿时不会把库存加多。</p>
     */
    @Override
    public void rollbackRedisReservation(VoucherOrder order) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId();
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId();

        Long removed = stringRedisTemplate.opsForSet()
                .remove(orderKey, order.getUserId().toString());
        if (removed != null && removed > 0) {
            stringRedisTemplate.opsForValue().increment(stockKey);
        }
    }
}
