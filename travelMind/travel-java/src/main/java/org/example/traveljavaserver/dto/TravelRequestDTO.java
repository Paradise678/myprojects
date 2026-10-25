package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 行程规划请求参数 DTO（Data Transfer Object）
 * 前端 POST /recommend 时传入的参数，后端用 @Valid 校验。
 */
@Data
public class TravelRequestDTO {
    @NotNull(message = "城市不能为空")
    private String city;                       // 目的地城市

    @NotNull(message = "天数不能为空")
    @Min(value = 1, message = "天数不能小于1")
    @Max(value = 30, message = "天数不能大于30")
    private Integer days;                      // 旅行天数（1~30）

    @NotNull(message = "预算不能为空")
    @DecimalMin(value="100", message = "预算不能小于100")
    private Double budget;                     // 预算金额（最低 100 元）
}
