package org.example.traveljavaserver.metrics;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用指标监听器（Micrometer → Prometheus）
 * 挂在 OpenAiChatModel / OpenAiStreamingChatModel 上，对每次大模型调用自动打点：
 *   - llm_call_total{result=success|error}   调用次数（Counter）
 *   - llm_latency{result=success|error}      调用耗时（Timer，Prometheus 中呈现为 llm_latency_seconds）
 *   - llm_tokens{type=input|output}          Token 消耗（Counter）
 *
 * 耗时起点通过 attributes() 在 onRequest → onResponse/onError 之间传递（同一 Map 实例贯穿一次调用）。
 */
@Component
@RequiredArgsConstructor
public class LlmMetricsListener implements ChatModelListener {

    private static final String START_TIME = "llm_start_time";

    private final MeterRegistry registry;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        requestContext.attributes().put(START_TIME, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        recordLatency(responseContext.attributes(), "success");
        Counter.builder("llm_call_total").tags("result", "success").register(registry).increment();

        // Token 用量：非流式响应必然携带；流式响应取决于平台是否回传 usage，需判空
        if (responseContext.chatResponse() != null
                && responseContext.chatResponse().metadata() != null) {
            TokenUsage usage = responseContext.chatResponse().metadata().tokenUsage();
            if (usage != null) {
                if (usage.inputTokenCount() != null) {
                    Counter.builder("llm_tokens").tags("type", "input").register(registry)
                            .increment(usage.inputTokenCount());
                }
                if (usage.outputTokenCount() != null) {
                    Counter.builder("llm_tokens").tags("type", "output").register(registry)
                            .increment(usage.outputTokenCount());
                }
            }
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        recordLatency(errorContext.attributes(), "error");
        Counter.builder("llm_call_total").tags("result", "error").register(registry).increment();
    }

    private void recordLatency(java.util.Map<Object, Object> attributes, String result) {
        Object start = attributes.get(START_TIME);
        if (start instanceof Long) {
            Timer.builder("llm_latency").tags("result", result).register(registry)
                    .record(System.nanoTime() - (Long) start, TimeUnit.NANOSECONDS);
        }
    }
}
