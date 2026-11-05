package com.shophub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户登录表单")
public class LoginFormDTO {

    @Schema(description = "手机号", example = "13800138000", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Schema(description = "短信验证码（phone+code 登录方式）", example = "123456")
    private String code;

    @Schema(description = "密码（phone+password 登录方式）")
    private String password;
}
