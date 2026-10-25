package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 事务消息的本地事务：扣 DB 库存 + 写订单（幂等）。
     */
    boolean saveOrderInTransaction(VoucherOrder order);

    /**
     * 判断订单是否已落库，供 RocketMQ 事务回查使用。
     */
    boolean orderExists(Long orderId);

    /**
     * 回滚 Redis 预扣库存和用户占位（幂等）。
     */
    void rollbackRedisReservation(VoucherOrder order);
}
