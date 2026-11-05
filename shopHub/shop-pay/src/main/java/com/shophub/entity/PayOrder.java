package com.shophub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付订单表
 * 两阶段状态机：PENDING(待支付) → PAYING(支付中) → PAID(已支付) / FAILED(失败) / REFUNDED(退款)
 */
@Data
@TableName("tb_pay_order")
public class PayOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务订单号 (关联秒杀订单) */
    private String orderNo;

    /** 支付单号 (支付侧唯一标识) */
    private String payNo;

    /** 支付用户ID */
    private Long userId;

    /** 支付金额 (单位: 分) */
    private Integer amount;

    /** 支付渠道: WECHAT / ALIPAY / SIMULATE */
    private String channel;

    /**
     * 状态: 0-待支付 1-支付中 2-已支付 3-支付失败 4-已退款
     */
    private Integer status;

    /** 渠道回调原始报文 (幂等校验用) */
    private String callbackData;

    /** 回调报文 SHA256 哈希 (回调幂等去重唯一约束) */
    private String callbackHash;

    /** 支付成功时间 */
    private LocalDateTime payTime;

    /** 支付过期时间 */
    private LocalDateTime expireTime;

    /** 退款时间（status=4 时有值） */
    private LocalDateTime refundTime;

    /** 退款金额（分，部分退款场景） */
    private Integer refundAmount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ========== 状态常量 ==========
    public static final int STATUS_PENDING = 0;   // 待支付
    public static final int STATUS_PAYING = 1;   // 支付中
    public static final int STATUS_PAID = 2;     // 已支付
    public static final int STATUS_FAILED = 3;   // 支付失败
    public static final int STATUS_REFUNDED = 4; // 已退款
}
