package com.shophub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 发起支付请求 DTO (Dubbo Provider / HTTP Controller 共用)
 */
@Data
@Schema(description = "发起支付请求参数")
public class PayCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务订单号 (关联秒杀订单) */
    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "业务订单号（秒杀订单ID）", example = "65519642386177307", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;

    /** 支付用户ID */
    @NotNull(message = "userId 不能为空")
    @Min(value = 1, message = "userId 必须为正数")
    @Schema(description = "支付用户ID", example = "1011", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    /** 支付金额 (单位: 分)，范围 [1, 100000000] (100万元) */
    @NotNull(message = "amount 不能为空")
    @Min(value = 1, message = "amount 必须 >= 1 分")
    @Schema(description = "支付金额（单位：分），范围 1~100000000", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer amount;

    /** 支付渠道: WECHAT / ALIPAY / SIMULATE */
    @Schema(description = "支付渠道：WECHAT / ALIPAY / SIMULATE（默认 SIMULATE）", example = "SIMULATE")
    private String channel;

    /** 支付过期时间 (秒)，默认 1800 (30 分钟)，范围 [300, 86400] (5分钟~24小时) */
    @Schema(description = "支付过期时间（秒），默认 1800（30分钟），范围 300~86400", example = "1800")
    private Integer expireSeconds;
}
