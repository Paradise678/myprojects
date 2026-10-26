package org.example.traveljavaserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 流式响应 - 引用来源 VO
 * 对应 SSE 推送格式：{"type":"citations","sources":["hangzhou.md","tips.md"]}
 * 在流结束（done）前推送一条，前端可在回答末尾展示"资料来源"。
 */
@Data
@AllArgsConstructor
public class StreamCitationsVO {
    private String type = "citations";   // 消息类型固定为 citations
    private List<String> sources;        // 本次回答引用的知识库文档名列表

    public static StreamCitationsVO of(List<String> sources) {
        return new StreamCitationsVO("citations", sources);
    }
}
