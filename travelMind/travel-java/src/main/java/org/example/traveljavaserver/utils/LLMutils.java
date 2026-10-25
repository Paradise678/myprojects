package org.example.traveljavaserver.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.internal.http2.Hpack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 大模型（LLM）调用工具类
 * 封装了对 SiliconFlow 平台兼容 OpenAI 格式接口的调用：
 *   - chat()       普通同步调用，一次性返回完整回复（用于行程规划）
 *   - chatStream() 流式调用，逐块返回（用于 AI 对话）
 *
 * 接口地址：{baseUrl}/chat/completions
 * 鉴权方式：HTTP Header 中携带 Authorization: Bearer {apiKey}
 */
public class LLMutils {
    private String apiKey;     // API 密钥
    private String baseUrl;    // API 基础地址
    private String model;      // 模型名称
    private OkHttpClient client;  // HTTP 客户端
    private ObjectMapper objectMapper = new ObjectMapper();  // JSON 解析工具

    public LLMutils(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        // 配置 OkHttp 超时：连接 60s，读取 120s（大模型生成较慢），写入 60s
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 普通同步调用大模型（非流式）
     * 发送请求后阻塞等待，拿到完整响应后提取出文本内容返回。
     *
     * @param systemPrompt 系统提示词（设定 AI 角色），可为 null
     * @param userPrompt   用户提示词（具体需求）
     * @return 大模型返回的文本内容
     * @throws RuntimeException 请求失败或响应非 2xx 时抛出
     */
    public String chat(String systemPrompt, String userPrompt) {
        String requestBody = buildRequestBody(systemPrompt, userPrompt, false);
//        System.out.println(requestBody);
        // 构建 POST 请求
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)   // 鉴权
                .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(request).execute()) {
        if (!response.isSuccessful()) {
                // 非 2xx 响应（如 401 鉴权失败、404 模型不存在）抛出异常
                throw new IOException("LLM调用异常：" + response.code());
            }
//            System.out.println(response.body().string());
             String responseBody = response.body().string();
             // 从响应 JSON 中提取出 message.content 字段（即模型生成的文本）
             return extractContent(responseBody);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 从大模型响应 JSON 中提取文本内容
     * 响应结构：{ choices: [ { message: { content: "..." } } ] }
     */
    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText();
        }
        return "";
    }

    /**
     * 构建请求体 JSON 字符串
     * 按 OpenAI 兼容格式组装 model、stream、messages、temperature 等字段。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param stream       是否流式输出
     * @return 请求体 JSON 字符串
     */
    public String buildRequestBody(String systemPrompt, String userPrompt, Boolean stream) {
        StringBuilder str = new StringBuilder();
        str.append("{");
        str.append("\"model\":\"" + model + "\",");
        str.append("\"stream\":" + stream + ",");
        str.append("\"messages\":[");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            str.append("{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},");
        }
        str.append("{\"role\":\"user\",\"content\":\"" + escapeJson(userPrompt) + "\"}");
        str.append("],");
        str.append("\"temperature\":0.7");
        str.append("}");
        return str.toString();
    }

    /**
     * JSON 字符串转义：把提示词中的特殊字符转义，避免破坏 JSON 结构
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")      // 转义反斜杠
                .replace("\"", "\\\"")      // 转义双引号
                .replace("\n", "\\n")       // 转义换行符
                .replace("\r", "\\r")       // 转义回车符
                .replace("\t", "\\t");      // 转义制表符
    }

    /**
     * 流式调用大模型
     * 逐行读取 SSE 响应（data: {...}），每解析出一段内容就调用 callback 回调。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param callback     每收到一段内容的回调，参数为该段文本
     * @return 完整的回复文本（所有分片拼接结果）
     */
    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> callback) throws IOException {
        String requestBody = buildRequestBody(systemPrompt, userPrompt, true);
        // 构建请求，Accept 设为 text/event-stream 表明期望流式响应
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                .build();
        // 记录完整的回复内容
        StringBuilder fullContent = new StringBuilder();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM调用异常：" + response.code());
            }
            // 以 UTF-8 逐行读取响应流
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        // SSE 格式：每行以 "data: " 开头，后面是 JSON 数据
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) {
                            // 流结束标记
                            break;
                        }
                        // 解析出当前分片的文本内容
                        String content = parseStreamContent(data);
                        if (content != null && !content.isEmpty()) {
                            fullContent.append(content);
                            if (callback != null) {
                                callback.accept(content);  // 回调通知上层
                            }
                        }
                    }
                }
            }
        }
        return fullContent.toString();
    }

    /**
     * 解析流式响应中单个分片的文本内容
     * 流式响应结构：{ choices: [ { delta: { content: "..." } } ] }
     * 注意与非流式不同，流式里是 delta 而非 message
     */
    private String parseStreamContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta");
                return delta.path("content").asText("");
            }
        } catch (Exception e) {
            System.out.println("解析流式数据失败: {}" + e.getMessage());
        }
        return null;
    }
}
