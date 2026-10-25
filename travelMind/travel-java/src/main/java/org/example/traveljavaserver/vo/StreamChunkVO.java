package org.example.traveljavaserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 流式响应 - 内容分片 VO
 * 对应 SSE 推送格式：{"type":"chunk","content":"一段文本"}
 * 前端每收到一条这样的消息，就把 content 追加到 AI 消息气泡上。
 */
@Data
@AllArgsConstructor
public class StreamChunkVO {
    private String type = "chunk";   // 消息类型固定为 chunk
    private String content;          // 本分片的文本内容

    /** 静态工厂方法：快速创建一个分片对象 */
    public static StreamChunkVO of(String content) {
        return new StreamChunkVO("chunk", content);
    }
}
