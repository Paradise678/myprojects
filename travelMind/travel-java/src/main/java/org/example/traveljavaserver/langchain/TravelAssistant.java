package org.example.traveljavaserver.langchain;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.example.traveljavaserver.vo.TravelRecommendVO;

/**
 * LangChain4j AiServices 接口
 * 由 LangChainConfig 通过 AiServices.builder 创建为 Spring Bean。
 *
 *   1. plan() —— 行程规划：Prompt 模板外置在 resources/prompts/travel-plan.txt，
 *      返回类型为 POJO，LangChain4j 自动将 JSON Schema 注入提示词并把模型回复反序列化为 TravelRecommendVO。
 *      context 参数为 RAG 检索到的知识库片段，作为背景信息提升景点/门票/美食推荐的准确性。
 *   2. chat() —— AI 流式对话：返回 TokenStream，通过回调逐块获取模型输出。
 *      context 参数同样为 RAG 检索片段，注入到 System Prompt 中，回答优先基于参考资料。
 */
public interface TravelAssistant {

    @SystemMessage("你是一个专业的旅游规划师，擅长根据用户的需求生成详细的旅行行程。")
    @UserMessage(fromResource = "prompts/travel-plan.txt")
    TravelRecommendVO plan(@V("city") String city, @V("days") Integer days,
                           @V("budget") Double budget, @V("context") String context);

    @SystemMessage(fromResource = "prompts/chat-system.txt")
    TokenStream chat(@MemoryId String sessionId, @V("context") String context, @UserMessage String message);
}
