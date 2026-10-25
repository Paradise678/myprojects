package org.example.traveljavaserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.traveljavaserver.utils.LLMutils;
import org.example.traveljavaserver.vo.StreamChunkVO;
import org.example.traveljavaserver.vo.StreamDoneVO;
import org.example.traveljavaserver.vo.StreamErrorVO;
import org.springframework.beans.factory.annotation.Value;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;


/**
 * 旅游业务 Service 层
 * 职责：
 *   1. recommend() —— 构建提示词 → 调用大模型 → 解析返回的 JSON → 封装为行程 VO
 *   2. chat()      —— 以 SSE 流式方式调用大模型，逐块推送给前端
 */
@Service
public class TravelService {
    // 从 application.yml 注入 LLM 配置
    @Value("${llm.api-key}")
    private String apiKey;
    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.model}")
    private String model;

    // 大模型调用工具类
    private LLMutils llmUtils;
    // JSON 序列化/反序列化工具
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @PostConstruct：Bean 初始化完成后自动执行，用于创建 LLMutils 实例
     * （因为 LLMutils 不是 Spring 管理的 Bean，需要手动 new，且依赖注入完成后才有值）
     */
    @PostConstruct
    public void init() {
        this.llmUtils = new LLMutils(apiKey, baseUrl, model);
    }


    /**
     * 生成旅游行程规划
     * 流程：构建提示词 → 调用大模型 → 从模型回复中提取 JSON → 反序列化为 TravelRecommendVO
     *
     * @param city   目的地城市
     * @param days   旅行天数
     * @param budget 预算金额
     * @return TravelRecommendVO 结构化的行程数据；失败时 success=false 并携带 error 信息
     */
    public TravelRecommendVO recommend (String city, Integer days, Double budget) {
        TravelRecommendVO result = new TravelRecommendVO();
        //构建提示词
        String prompt = buildTravelPrompt(city, budget, days);
        try {
            // 调用大模型获取原始文本回复
            String response = llmUtils.chat(null, prompt);
            // 从模型回复中提取 JSON 并 反序列化为 TravelRecommendVO
            return parseTravelResponse(response);
        } catch (Exception e) {
            // 任何异常（网络错误、JSON 解析失败等）都返回失败结果，避免接口 500
            e.printStackTrace();
            result.setSuccess(false);
            result.setError("旅游推荐失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 解析大模型返回的行程文本，提取 JSON 并反序列化为 TravelRecommendVO
     * 大模型可能会在 JSON 外层包裹 ```json ... ``` 或带有额外文字，需要做提取处理。
     */
    private TravelRecommendVO parseTravelResponse(String response) {
        TravelRecommendVO result = new TravelRecommendVO();

        try {
            // 从模型回复中提取出纯 JSON 字符串
            String jsonContent = extractJson(response);
            if (jsonContent != null) {
                // 将 JSON 反序列化为 TravelRecommendVO 对象
                result = objectMapper.readValue(jsonContent, TravelRecommendVO.class);
            } else {
                // 没提取到 JSON，标记失败并保留原始回复便于排查
                result.setSuccess(false);
                result.setError("未能从响应中提取JSON");
                result.setRawResponse(response);
            }
        } catch (Exception e) {
            // JSON 格式错误导致反序列化失败
            result.setSuccess(false);
            result.setError("JSON解析失败");
            result.setRawResponse(response);
        }

        return result;
    }

    /**
     * 从大模型回复文本中提取 JSON 内容
     * 优先匹配 ```json ... ``` 代码块格式，匹配不到则取第一个 { 到最后一个 } 之间的内容。
     *
     * @param response 大模型原始回复
     * @return 提取出的 JSON 字符串；提取不到返回 null
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // 支持两种代码块格式：```json\n...\n``` 和 ```\n...\n```
        String[] patterns = {
                "```json\\n([\\s\\S]*?)\\n```",
                "```\\n([\\s\\S]*?)\\n```"
        };

        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(response);
            if (m.find()) {
                return m.group(1);
            }
        }

        // 兜底：直接截取第一个 { 到最后一个 } 之间的内容
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }

        return null;
    }

    /**
     * 构建行程规划的提示词（Prompt）
     * 告诉大模型它的角色、用户需求，以及要求输出的 JSON 结构，
     * 确保模型返回的内容能被前端正确解析和渲染。
     */
    private String buildTravelPrompt(String city, Double budget, Integer days) {
        return "你是一个专业的旅游规划师，擅长根据用户的需求生成详细的旅行行程。\n\n" +
                "请根据以下信息为用户生成一份详细的旅游规划：\n" +
                "- 目的地城市：" + city + "\n" +
                "- 预算：" + budget + "元\n" +
                "- 旅行天数：" + days + "天\n\n" +
                "要求：\n" +
                "1. 每天的行程安排（上午、下午、晚上）\n" +
                "2. 每个景点的详细介绍\n" +
                "3. 交通建议\n" +
                "4. 预算分配明细\n" +
                "5. 注意事项\n\n" +
                "请以JSON格式输出，结构如下：\n" +
                "{\n" +
                "  \"success\": true,\n" +
                "  \"city\": \"城市名\",\n" +
                "  \"days\": 天数,\n" +
                "  \"totalBudget\": 总预算,\n" +
                "  \"dailyItinerary\": [\n" +
                "    {\n" +
                "      \"day\": 1,\n" +
                "      \"date\": \"第1天\",\n" +
                "      \"morning\": {\n" +
                "        \"spot\": \"景点名称\",\n" +
                "        \"duration\": \"游览时长\",\n" +
                "        \"ticket\": \"门票价格\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"景点介绍\"\n" +
                "      },\n" +
                "      \"afternoon\": {\n" +
                "        \"spot\": \"景点名称\",\n" +
                "        \"duration\": \"游览时长\",\n" +
                "        \"ticket\": \"门票价格\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"景点介绍\"\n" +
                "      },\n" +
                "      \"evening\": {\n" +
                "        \"spot\": \"活动名称\",\n" +
                "        \"duration\": \"活动时长\",\n" +
                "        \"ticket\": \"费用\",\n" +
                "        \"transportation\": \"交通方式\",\n" +
                "        \"description\": \"活动介绍\"\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"budgetBreakdown\": {\n" +
                "    \"accommodation\": 住宿费用,\n" +
                "    \"food\": 餐饮费用,\n" +
                "    \"transportation\": 交通费用,\n" +
                "    \"tickets\": 门票费用,\n" +
                "    \"other\": 其他费用\n" +
                "  },\n" +
                "  \"tips\": [\"提示1\", \"提示2\", \"提示3\"],\n" +
                "  \"warnings\": [\"注意事项1\", \"注意事项2\"]\n" +
                "}\n\n" +
                "请确保JSON格式正确，可以被解析。";
    }

    /**
     * AI 流式对话
     * 使用 SseEmitter 在新线程中调用大模型流式接口，
     * 每收到一段内容就通过 emitter.send() 推送给前端，实现实时显示。
     *
     * @param message 用户发送的消息
     * @return SseEmitter 用于持续向前端推送数据的发射器
     */
    public SseEmitter chat(String message) {
        // 超时时间设为 180 秒，防止长对话被过早断开
        SseEmitter emitter = new SseEmitter(180000L);

        // 开启新线程异步处理，避免阻塞 Controller 的请求线程
        new Thread(() -> {
            try {
                String systemPrompt = "你是一个友好的旅游助手，请用中文回答用户关于旅游的问题。";

                // 流式回调：每收到一段模型输出，就序列化为 JSON 并推送给前端
                Consumer<String> callback = content -> {
                    try {
                       String json = objectMapper.writeValueAsString(StreamChunkVO.of(content));
                       emitter.send(SseEmitter.event().data(json));
                    } catch (Exception e) {
                        System.out.println("发送消息失败：" + e);
                    }
                };
                // 调用大模型流式接口，callback 会被反复调用
                llmUtils.chatStream(systemPrompt, message, callback);
                // 流结束：发送完成标记并关闭 emitter
                String doneJson = objectMapper.writeValueAsString(StreamDoneVO.of());
                emitter.send(SseEmitter.event().data(doneJson));
                emitter.complete();
            }
            catch (Exception e) {
                // 出错：发送错误信息给前端，然后异常关闭 emitter
                try {
                    String errorJson = objectMapper.writeValueAsString(StreamErrorVO.of(e.getMessage()));
                    emitter.send(SseEmitter.event().data(errorJson));
                } catch (Exception e1) {
                    System.out.println("发送错误消息失败：" + e1);
                }
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}
