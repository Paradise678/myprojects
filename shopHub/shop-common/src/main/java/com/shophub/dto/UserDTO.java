package com.shophub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户信息（精简版，返回给前端）")
public class UserDTO {

    @Schema(description = "用户ID", example = "1011")
    private Long id;

    @Schema(description = "用户昵称", example = "user_GOHmUP11")
    private String nickName;

    @Schema(description = "头像URL")
    private String icon;
}
