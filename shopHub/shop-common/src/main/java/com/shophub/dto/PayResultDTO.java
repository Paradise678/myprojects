package com.shophub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付结果 DTO
 */
@Data
@Schema(description = "支付结果/支付单详情")
public class PayResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 支付单号 */
    @Schema(description = "支付单号（雪花ID）", example = "PAY17935152164742821")
    private String payNo;

    /** 业务订单号 */
    @Schema(description = "业务订单号/秒杀订单ID", example = "65519642386177307")
    private String orderNo;

    /** 状态: 0=PENDING待支付, 1=PROCESSING处理中, 2=PAID已支付, 3=FAILED失败, 4=REFUNDED已退款 */
    @Schema(description = "支付状态：0=待支付, 1=处理中, 2=已支付, 3=失败, 4=已退款", example = "2")
    private Integer status;

    /** 状态描述 */
    @Schema(description = "支付状态中文描述", example = "已支付")
    private String statusDesc;

    /** 支付二维码/链接 (WECHAT/ALIPAY) */
    @Schema(description = "支付链接/二维码URL（模拟渠道为文本链接）", example = "SIMULATE://pay/PAY17935152164742821")
    private String payUrl;

    /** 过期时间 */
    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;

    /** 支付成功时间 */
    @Schema(description = "支付成功时间")
    private LocalDateTime payTime;

    // ========== 快捷构造 ==========
    public static PayResultDTO success(String payNo, String orderNo, LocalDateTime payTime) {
        PayResultDTO r = new PayResultDTO();
        r.setPayNo(payNo);
        r.setOrderNo(orderNo);
        r.setStatus(2);
        r.setStatusDesc("已支付");
        r.setPayTime(payTime);
        return r;
    }

    public static PayResultDTO pending(String payNo, String orderNo, String payUrl, LocalDateTime expireTime) {
        PayResultDTO r = new PayResultDTO();
        r.setPayNo(payNo);
        r.setOrderNo(orderNo);
        r.setStatus(0);
        r.setStatusDesc("待支付");
        r.setPayUrl(payUrl);
        r.setExpireTime(expireTime);
        return r;
    }

    public static PayResultDTO failed(String payNo, String orderNo) {
        PayResultDTO r = new PayResultDTO();
        r.setPayNo(payNo);
        r.setOrderNo(orderNo);
        r.setStatus(3);
        r.setStatusDesc("支付失败");
        return r;
    }
}
