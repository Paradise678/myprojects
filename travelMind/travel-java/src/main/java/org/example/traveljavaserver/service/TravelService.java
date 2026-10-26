package org.example.traveljavaserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.entity.TravelPlan;
import org.example.traveljavaserver.langchain.RedisChatMemoryStore;
import org.example.traveljavaserver.langchain.TravelAssistant;
import org.example.traveljavaserver.mapper.TravelPlanMapper;
import org.example.traveljavaserver.vo.StreamCitationsVO;
import org.example.traveljavaserver.vo.StreamChunkVO;
import org.example.traveljavaserver.vo.StreamDoneVO;
import org.example.traveljavaserver.vo.StreamErrorVO;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 旅游业务 Service 层
 * 职责：
 *   1. recommend() —— 手动执行 RAG 检索（拿到引用来源），通过 LangChain4j AiServices 调用大模型
 *      （含 Function Calling 工具链），结构化输出反序列化为行程 VO；带 Resilience4j 熔断保护
 *   2. chat()      —— 手动执行 RAG 检索，以 SSE 流式方式调用大模型，TokenStream 回调逐块推送，
 *      流结束前推送 citations 事件（引用的知识库文档），按 sessionId 维护会话记忆（Redis）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelService {

    // 匿名会话默认 ID（前端未传 sessionId 时使用）
    private static final String DEFAULT_SESSION = "anonymous";

    // 城市关键词 -> 拼音映射，用于从查询文本中识别目的地城市，实现检索时按城市过滤
    private static final Map<String, String> CITY_KEYWORDS = Map.ofEntries(
            Map.entry("北京", "beijing"), Map.entry("beijing", "beijing"),
            Map.entry("上海", "shanghai"), Map.entry("shanghai", "shanghai"),
            Map.entry("杭州", "hangzhou"), Map.entry("hangzhou", "hangzhou"),
            Map.entry("成都", "chengdu"), Map.entry("chengdu", "chengdu"),
            Map.entry("西安", "xian"), Map.entry("xian", "xian")
    );

    // LangChain4j AiServices 代理，大模型调用的唯一入口
    private final TravelAssistant travelAssistant;
    // 行程落库
    private final TravelPlanMapper travelPlanMapper;
    // RAG：Embedding 模型与向量库，用于按需构建带城市过滤的检索器
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    // P7.5：会话记忆存储（读取历史用于查询改写）与查询改写器
    private final RedisChatMemoryStore chatMemoryStore;
    private final CompressingQueryTransformer queryTransformer;
    // JSON 序列化工具（SSE 推送内容需要序列化为 JSON 字符串）
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * RAG 检索结果：拼接后的上下文字符串 + 引用来源文档名列表（去重）
     */
    private record RagContext(String context, List<String> sources) {}

    /**
     * 从查询文本中识别城市拼音。支持中文名与拼音，命中首个即返回；未命中返回 null。
     */
    private String detectCity(String query) {
        if (query == null) {
            return null;
        }
        for (Map.Entry<String, String> e : CITY_KEYWORDS.entrySet()) {
            if (query.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 上下文感知查询改写（P7.5）：
     * 多轮对话中追问往往不含城市名（如"第二天去哪""门票多少钱"），直接检索会导致
     * 城市过滤失效、向量召回混入其他城市片段。此处将会话历史 + 当前消息交给
     * CompressingQueryTransformer 压缩为语义完整的独立查询。
     *
     * 改写仅在「消息本身未命中城市」且「存在会话历史」时触发，避免无谓的 LLM 调用；
     * 改写失败时降级为原始消息，不影响主流程。
     */
    private String expandQuery(String memoryId, String message) {
        if (detectCity(message) != null) {
            return message;
        }
        try {
            List<ChatMessage> history = chatMemoryStore.getMessages(memoryId);
            if (history == null || history.isEmpty()) {
                return message;
            }
            Query query = Query.from(message,
                    Metadata.from(UserMessage.from(message), memoryId, history));
            Query compressed = queryTransformer.transform(query).stream().findFirst().orElse(null);
            if (compressed != null && StringUtils.hasText(compressed.text())) {
                log.info("[RAG] 查询改写: '{}' -> '{}'", message, compressed.text());
                return compressed.text();
            }
        } catch (Exception e) {
            log.warn("[RAG] 查询改写失败，使用原始查询: {}", e.getMessage());
        }
        return message;
    }

    /**
     * 手动执行 RAG 检索，返回上下文文本与引用来源。
     * 若查询命中已知城市，则检索时附加 city 过滤（同时保留通用常识 common），消除跨城市引用噪声。
     * 检索失败时降级为空上下文，不影响主流程。
     */
    private RagContext retrieveContext(String queryText) {
        try {
            String city = detectCity(queryText);
            // 按需构建检索器：命中城市时只召回该城市 + 通用常识的片段
            var retrieverBuilder = EmbeddingStoreContentRetriever.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .maxResults(5)
                    .minScore(0.6);
            if (city != null) {
                retrieverBuilder.filter(Filter.or(
                        MetadataFilterBuilder.metadataKey("city").isEqualTo(city),
                        MetadataFilterBuilder.metadataKey("city").isEqualTo("common")));
                log.debug("[RAG] 命中城市过滤: city={}", city);
            }
            List<Content> contents = retrieverBuilder.build().retrieve(Query.from(queryText));
            if (contents == null || contents.isEmpty()) {
                return new RagContext("（暂无相关参考资料）", List.of());
            }
            List<String> sources = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (Content c : contents) {
                String src = c.textSegment().metadata().getString("source");
                if (src == null || src.isBlank()) {
                    src = "unknown";
                }
                if (!sources.contains(src)) {
                    sources.add(src);
                }
                sb.append("[").append(idx++).append("] ")
                        .append(c.textSegment().text()).append("\n");
            }
            return new RagContext(sb.toString(), sources);
        } catch (Exception e) {
            log.warn("[RAG] 检索失败，降级为无上下文: {}", e.getMessage());
            return new RagContext("（参考资料检索失败）", List.of());
        }
    }

    /**
     * 生成旅游行程规划
     * 先按城市名检索知识库（景点/门票/美食/交通），再把上下文传给模型生成结构化行程。
     */
    @CircuitBreaker(name = "llm", fallbackMethod = "recommendFallback")
    public TravelRecommendVO recommend(String city, Integer days, Double budget, Long userId) {
        try {
            RagContext rag = retrieveContext(city + " 旅游景点 门票 美食 交通");
            TravelRecommendVO result = travelAssistant.plan(city, days, budget, rag.context());
            // success/error/rawResponse 是服务端控制字段，不采用模型填充值
            result.setSuccess(true);
            result.setError(null);
            result.setRawResponse(null);
            // 引用来源由服务端填充，不依赖模型输出
            result.setCitations(rag.sources());
            // 行程落库：保存完整规划 JSON，供历史行程查询
            TravelPlan plan = new TravelPlan();
            plan.setUserId(userId);
            plan.setCity(city);
            plan.setDays(days);
            plan.setTotalBudget(budget);
            plan.setPlanJson(objectMapper.writeValueAsString(result));
            plan.setCreateTime(LocalDateTime.now());
            travelPlanMapper.insert(plan);
            return result;
        } catch (Exception e) {
            // 任何异常（网络错误、JSON 解析失败等）都返回失败结果，避免接口 500
            TravelRecommendVO result = new TravelRecommendVO();
            result.setSuccess(false);
            result.setError("旅游推荐失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 熔断降级方法：LLM 服务持续不可用时直接返回失败提示，不再发起真实调用
     */
    public TravelRecommendVO recommendFallback(String city, Integer days, Double budget, Long userId, Throwable t) {
        TravelRecommendVO result = new TravelRecommendVO();
        result.setSuccess(false);
        result.setError("行程规划服务暂时不可用（已熔断），请稍后重试");
        return result;
    }

    /**
     * AI 流式对话
     * 先检索知识库，上下文注入 System Prompt；TokenStream 回调逐块推送，
     * 流结束前推送 citations（引用来源），再推送 done 关闭连接。
     */
    public SseEmitter chat(String sessionId, String message) {
        // 超时时间设为 180 秒，防止长对话被过早断开
        SseEmitter emitter = new SseEmitter(180000L);
        String memoryId = StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION;

        // 检索在调用线程执行，结果可直接用于 citations；
        // 先做上下文感知查询改写，使不含城市的追问也能命中正确的城市过滤
        RagContext rag = retrieveContext(expandQuery(memoryId, message));
        if (!rag.sources().isEmpty()) {
            log.info("[RAG] 命中引用来源: {}", rag.sources());
        }

        travelAssistant.chat(memoryId, rag.context(), message)
                // 流式回调：每收到一段模型输出，就序列化为 JSON 并推送给前端
                .onPartialResponse(content -> {
                    try {
                        String json = objectMapper.writeValueAsString(StreamChunkVO.of(content));
                        emitter.send(SseEmitter.event().data(json));
                    } catch (Exception e) {
                        log.warn("发送消息失败：{}", e.getMessage());
                    }
                })
                // 流结束：先推送引用来源，再发送完成标记并关闭 emitter
                .onCompleteResponse(response -> {
                    try {
                        if (!rag.sources().isEmpty()) {
                            String citationsJson = objectMapper.writeValueAsString(StreamCitationsVO.of(rag.sources()));
                            emitter.send(SseEmitter.event().data(citationsJson));
                        }
                        String doneJson = objectMapper.writeValueAsString(StreamDoneVO.of());
                        emitter.send(SseEmitter.event().data(doneJson));
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                // 出错：发送错误信息给前端，然后异常关闭 emitter
                .onError(error -> {
                    try {
                        String errorJson = objectMapper.writeValueAsString(StreamErrorVO.of(error.getMessage()));
                        emitter.send(SseEmitter.event().data(errorJson));
                    } catch (Exception e) {
                        log.warn("发送错误消息失败：{}", e.getMessage());
                    }
                    emitter.completeWithError(error);
                })
                .start();

        return emitter;
    }

    /**
     * 查询当前用户的历史行程列表（按创建时间倒序）
     */
    public List<org.example.traveljavaserver.entity.TravelPlan> listPlans(Long userId) {
        return travelPlanMapper.selectList(
                new LambdaQueryWrapper<org.example.traveljavaserver.entity.TravelPlan>()
                        .eq(org.example.traveljavaserver.entity.TravelPlan::getUserId, userId)
                        .orderByDesc(org.example.traveljavaserver.entity.TravelPlan::getCreateTime));
    }
}
