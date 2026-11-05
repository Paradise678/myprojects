package com.shophub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shophub.entity.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付订单 Mapper
 */
@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /**
     * 幂等更新：只有当前状态符合预期才推进状态机
     * @param payNo       支付单号
     * @param fromStatus  期望当前状态
     * @param toStatus    目标状态
     * @param callbackData 渠道回调数据
     * @param payTime     支付时间
     * @return 更新行数 (0 表示状态已被其他请求推进，幂等命中)
     */
    @Update("UPDATE tb_pay_order " +
            "SET status = #{toStatus}, callback_data = #{callbackData}, pay_time = #{payTime} " +
            "WHERE pay_no = #{payNo} AND status = #{fromStatus}")
    int updateStatusIdempotent(@Param("payNo") String payNo,
                               @Param("fromStatus") int fromStatus,
                               @Param("toStatus") int toStatus,
                               @Param("callbackData") String callbackData,
                               @Param("payTime") LocalDateTime payTime);

    /**
     * 标记为支付失败
     */
    @Update("UPDATE tb_pay_order SET status = #{status}, callback_data = #{callbackData} " +
            "WHERE pay_no = #{payNo} AND status = #{fromStatus}")
    int markFailed(@Param("payNo") String payNo,
                   @Param("fromStatus") int fromStatus,
                   @Param("status") int status,
                   @Param("callbackData") String callbackData);

    /**
     * 查询已过期但仍 PENDING 的支付单（过期关闭定时任务用）
     * LIMIT 200 防止一次性拉爆内存
     */
    @Select("SELECT * FROM tb_pay_order " +
            "WHERE status = 0 AND expire_time < NOW() " +
            "ORDER BY expire_time ASC " +
            "LIMIT 200")
    List<PayOrder> selectExpiredOrders();

    /**
     * 乐观锁：标记过期失败（只有仍 PENDING 才能成功）
     * @return 更新行数，0 表示已被其他并发请求推进状态
     */
    @Update("UPDATE tb_pay_order SET status = 3, callback_data = #{callbackData} " +
            "WHERE pay_no = #{payNo} AND status = 0")
    int markExpired(@Param("payNo") String payNo,
                    @Param("callbackData") String callbackData);

    /**
     * 乐观锁：标记已退款（只有 PAID 才能退款）
     * @param refundAmount 退款金额 (分)
     * @param reason 退款原因
     * @return 更新行数，0 表示状态已不是 PAID（可能已退款/失败/过期）
     */
    @Update("UPDATE tb_pay_order SET status = 4, " +
            "callback_data = CONCAT('REFUND:', #{reason}), " +
            "refund_time = NOW(), " +
            "refund_amount = #{refundAmount} " +
            "WHERE pay_no = #{payNo} AND status = 2")
    int markRefunded(@Param("payNo") String payNo,
                     @Param("reason") String reason,
                     @Param("refundAmount") int refundAmount);

    /**
     * 乐观锁推进状态机 + 写 callbackHash 去重
     * WHERE 条件：status=预期 AND (callback_hash IS NULL 或 != 本次 hash)
     *   —— 双保险：防状态回滚 + 防同一报文重复处理
     */
    @Update("UPDATE tb_pay_order " +
            "SET status = #{toStatus}, " +
            "    callback_data = #{callbackData}, " +
            "    callback_hash = #{callbackHash}, " +
            "    pay_time = #{payTime} " +
            "WHERE pay_no = #{payNo} " +
            "  AND status = #{fromStatus} " +
            "  AND (callback_hash IS NULL OR callback_hash != #{callbackHash})")
    int updateCallbackIdempotent(@Param("payNo") String payNo,
                                 @Param("fromStatus") int fromStatus,
                                 @Param("toStatus") int toStatus,
                                 @Param("callbackData") String callbackData,
                                 @Param("callbackHash") String callbackHash,
                                 @Param("payTime") LocalDateTime payTime);

    /**
     * 乐观锁：取消 PENDING 支付单 → FAILED(3)
     * 用于用户主动取消 / 业务撤销（不等过期定时任务）
     */
    @Update("UPDATE tb_pay_order SET status = 3, callback_data = CONCAT('CANCEL:', #{reason}) " +
            "WHERE pay_no = #{payNo} AND status = 0")
    int markCancelled(@Param("payNo") String payNo,
                      @Param("reason") String reason);
}
