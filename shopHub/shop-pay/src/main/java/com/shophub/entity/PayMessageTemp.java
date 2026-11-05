package com.shophub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表 (Outbox 模式)
 * 保证 "DB 写操作" 与 "MQ 发送" 在同一个本地事务中原子性
 * 定时任务扫描 status=0 的记录，发送 MQ 后置为 1，失败则递增 retry_count
 */
@Data
@TableName("tb_pay_message_temp")
public class PayMessageTemp implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 消息类型: PAY_SUCCESS / PAY_FAIL / REFUND */
    private String msgType;

    /** 业务标识 (支付单号) */
    private String bizKey;

    /** 消息体 JSON */
    private String msgBody;

    /** RocketMQ Topic */
    private String mqTopic;

    /** RocketMQ Tag */
    private String mqTag;

    /** 状态: 0-待发送 1-已发送 2-发送失败 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ========== 状态常量 ==========
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;
}
