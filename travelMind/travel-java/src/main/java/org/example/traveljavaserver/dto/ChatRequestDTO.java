package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 对话请求参数 DTO
 * 前端 POST /chat 时传入的参数。
 */
@Data
public class ChatRequestDTO {
    @NotBlank(message = "消息不能为空")   // 校验：消息不能为空字符串
    private String message;              // 用户发送的消息内容
}
