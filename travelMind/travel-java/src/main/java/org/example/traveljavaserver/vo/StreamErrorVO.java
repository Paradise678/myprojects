package org.example.traveljavaserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 流式响应 - 错误信息 VO
 * 对应 SSE 推送格式：{"error":"错误描述"}
 * 后端流式处理过程中出错时推送给前端，前端据此显示错误提示。
 */
@Data
@AllArgsConstructor
public class StreamErrorVO {
    private String error;   // 错误描述信息

    public static StreamErrorVO of(String error) {
        return new StreamErrorVO(error);
    }
}
