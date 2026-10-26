package org.example.traveljavaserver.langchain;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.example.traveljavaserver.metrics.LlmMetricsListener;
import org.example.traveljavaserver.tools.TravelTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * LangChain4j 装配配置
 * 手动构建模型 Bean（starter 自动配置不支持注册监听器，手动构建后 starter 退让），统一挂载：
 *   - LlmMetricsListener：LLM 调用指标打点
 *   - maxTokens：限制单次回复最大 Token 数，控制成本
 * TravelAssistant 额外挂载：
 *   - TravelTools：Function Calling 工具集
 *   - chatMemoryProvider：按 sessionId 隔离的会话记忆（Redis db 1，最多保留 20 条）
 *   - contentRetriever：RAG 知识库检索（RedisEmbeddingStore db 2，持久化向量）
 */
@Configuration
public class LangChainConfig {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;
    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;
    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;
    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private Double temperature;
    @Value("${langchain4j.open-ai.chat-model.max-tokens}")
    private Integer maxTokens;

    // Embedding 模型：用于 RAG 文档向量化（硅基流动 BAAI/bge-m3，1024 维）
    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String embeddingModelName;

    // Redis 连接（复用 spring.data.redis 配置）
    // 注意：RediSearch 的 FT.CREATE 限制只能在 db 0 创建索引，故向量库放 db 0；
    // 会话记忆/限流使用 db 1，两者天然隔离。
    @Value("${spring.data.redis.host}")
    private String redisHost;
    @Value("${spring.data.redis.port}")
    private int redisPort;
    @Value("${spring.data.redis.password}")
    private String redisPassword;
    private static final int VECTOR_DB_INDEX = 0;       // 向量库所在 Redis DB（RediSearch 仅支持 db 0）
    private static final String VECTOR_INDEX_NAME = "travelmind_kb";  // RediSearch 索引名
    private static final int EMBEDDING_DIMENSION = 1024;             // bge-m3 输出维度

    @Bean
    public OpenAiChatModel chatModel(LlmMetricsListener metricsListener) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .listeners(List.of(metricsListener))
                .build();
    }

    @Bean
    public OpenAiStreamingChatModel streamingChatModel(LlmMetricsListener metricsListener) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .listeners(List.of(metricsListener))
                .build();
    }

    /** Embedding 模型 Bean：RAG 入库与检索共用 */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * 向量存储：RedisEmbeddingStore（依赖 RedisSearch 模块，即 redis-stack 镜像）
     * 持久化到 Redis db 2，与会话记忆/限流的 db 1 隔离；服务重启后向量数据不丢失。
     * 通过 uri 指定数据库（redis://:密码@主机:端口/数据库），索引不存在时由 build() 自动创建。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        String redisUri = String.format("redis://:%s@%s:%d/%d",
                redisPassword, redisHost, redisPort, VECTOR_DB_INDEX);
        return RedisEmbeddingStore.builder()
                .uri(redisUri)
                .indexName(VECTOR_INDEX_NAME)
                .dimension(EMBEDDING_DIMENSION)
                // 声明需要持久化并可检索的元数据字段：
                //   source = 文档名（用于 citations 来源透传）
                //   city   = 城市拼音（用于检索时按城市过滤，消除跨城市引用噪声）
                .metadataKeys(List.of("source", "city"))
                .build();
    }

    /**
     * 查询改写器（P7.5）：多轮对话中将不含指代信息的追问（如"第二天去哪""门票多少钱"）
     * 结合会话历史压缩为语义完整的独立查询，使城市过滤与向量检索仍能命中正确城市。
     * 复用非流式 chatModel（改写输出极短，成本可忽略）。
     */
    @Bean
    public CompressingQueryTransformer queryTransformer(OpenAiChatModel chatModel) {
        return new CompressingQueryTransformer(chatModel);
    }

    @Bean
    public TravelAssistant travelAssistant(OpenAiChatModel chatModel,
                                           OpenAiStreamingChatModel streamingChatModel,
                                           TravelTools travelTools,
                                           RedisChatMemoryStore chatMemoryStore) {
        return AiServices.builder(TravelAssistant.class)
                .chatLanguageModel(chatModel)
                .streamingChatLanguageModel(streamingChatModel)
                // 注册 Function Calling 工具集：模型生成行程前可自主多步调用天气/景点/预算工具
                .tools(travelTools)
                // RAG 检索由 TravelService 手动执行（以便拿到引用来源做 citations 透传），
                // 检索结果通过 @V("context") 注入 prompt，故此处不再挂 contentRetriever
                // 会话记忆：按 sessionId（@MemoryId）隔离，Redis 持久化，窗口最多 20 条消息
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .build();
    }
}
