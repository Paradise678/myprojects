package org.example.traveljavaserver.vo;

import lombok.Data;

/**
 * 流式响应 - 结束标记 VO
 * 对应 SSE 推送格式：{"done":true}
 * 前端收到后表示 AI 回复已全部接收完毕，可关闭 loading 状态。
 */
@Data
public class StreamDoneVO {
    private Boolean done = true;   // 固定为 true，表示流结束

    public static StreamDoneVO of() {
        return new StreamDoneVO();
    }
}
