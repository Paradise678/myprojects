package org.example.traveljavaserver.langchain;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库初始化器
 * 服务启动时扫描 classpath:docs/ 下的 Markdown 文档，切块并向量化后写入 EmbeddingStore，
 * 供 TravelService 手动检索使用。
 *
 * P7.1 阶段：使用 InMemoryEmbeddingStore，重启即重建；
 * P7.3 切换到 RedisEmbeddingStore 后，可通过 /api/travel/kb/reload 接口按需重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseInitializer {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载知识库：清空当前向量库 → 重新扫描 docs/ → 切块 → 向量化 → 入库。
     * 由 /api/travel/kb/reload 接口触发，用于更新知识库后热重载。
     *
     * @return 本次入库的片段总数
     */
    public int reload() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:docs/*.md");
            if (resources.length == 0) {
                log.warn("[KB] 未找到 docs/*.md 知识库文档，RAG 检索将无内容可召回");
                return 0;
            }

            List<TextSegment> allSegments = new ArrayList<>();
            // 递归切块：500 字符一块，重叠 50 字符，兼顾语义完整与检索粒度
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);

            for (Resource resource : resources) {
                // 运行在 fat jar 内时资源不在文件系统，必须用 InputStream 读取，避免 getFile() 抛 FileNotFoundException
                String content;
                try (InputStream is = resource.getInputStream()) {
                    content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                String filename = resource.getFilename();
                // metadata.source 记录文件名，供 citations 来源透传使用
                // metadata.city 记录城市拼音（如 chengdu.md -> chengdu；通用常识 tips.md -> common），
                // 供检索时按城市过滤，避免跨城市引用噪声
                String city = filename == null ? "common" : filename.replace(".md", "");
                if (!"beijing".equals(city) && !"shanghai".equals(city) && !"hangzhou".equals(city)
                        && !"chengdu".equals(city) && !"xian".equals(city)) {
                    city = "common";
                }
                Metadata metadata = Metadata.from("source", filename).put("city", city);
                Document doc = Document.from(content, metadata);
                List<TextSegment> segments = splitter.split(doc);
                log.info("[KB] 加载文档: {} -> {} 个片段 (city={})", filename, segments.size(), city);
                allSegments.addAll(segments);
            }

            // 先清空旧数据再批量向量化入库，避免重复片段
            embeddingStore.removeAll();
            Response<List<Embedding>> embedResp = embeddingModel.embedAll(allSegments);
            List<Embedding> embeddings = embedResp.content();
            embeddingStore.addAll(embeddings, allSegments);
            log.info("[KB] 知识库重载完成，共 {} 个片段向量化入库", allSegments.size());
            return allSegments.size();
        } catch (Exception e) {
            // 入库失败不影响服务启动（聊天降级为无 RAG），但必须记录错误便于排查
            log.error("[KB] 知识库重载失败", e);
            throw new RuntimeException("知识库重载失败：" + e.getMessage(), e);
        }
    }
}
