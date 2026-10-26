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

    /** 会话 ID：用于隔离会话记忆（Redis 存储）。不传则使用匿名会话，多用户共用 */
    private String sessionId;
}
