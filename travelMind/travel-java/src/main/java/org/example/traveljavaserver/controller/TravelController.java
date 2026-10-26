package org.example.traveljavaserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.ChatRequestDTO;
import org.example.traveljavaserver.langchain.KnowledgeBaseInitializer;
import org.example.traveljavaserver.ratelimit.RateLimiter;
import org.example.traveljavaserver.service.TravelService;
import org.example.traveljavaserver.vo.StreamErrorVO;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.web.bind.annotation.*;
import org.example.traveljavaserver.vo.Result;
import org.example.traveljavaserver.dto.TravelRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 旅游相关接口控制器
 * 所有接口统一前缀：/api/travel
 *
 * 提供两个核心能力：
 *   1. POST /recommend —— 根据城市/预算/天数，调用大模型生成完整行程规划（一次性返回 JSON）
 *   2. POST /chat      —— 与大模型进行流式对话（SSE，逐字返回）
 *
 * 两个接口均接入滑动窗口限流（Redis ZSet + Lua，多实例共享计数）。
 */
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor   // Lombok 注解：自动为 final 字段生成构造器，实现依赖注入
public class TravelController {
    private final TravelService travelService;
    private final RateLimiter rateLimiter;
    private final KnowledgeBaseInitializer knowledgeBaseInitializer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 健康检查接口：用于验证服务是否正常启动
     * GET /api/travel/hello → 返回 "hello world"
     */
    @GetMapping("/hello")
    public Result<String> hello () {
        return  Result.ok("hello world");
    }

    /**
     * 历史行程接口
     * GET /api/travel/plans
     * 返回当前登录用户的历史行程规划列表（按创建时间倒序），需 JWT 鉴权。
     */
    @GetMapping("/plans")
    public Result<java.util.List<org.example.traveljavaserver.entity.TravelPlan>> plans(HttpServletRequest request) {
        Long uid = (Long) request.getAttribute("uid");
        return Result.ok(travelService.listPlans(uid));
    }

    /**
     * 行程规划接口
     * POST /api/travel/recommend
     * 接收前端传来的城市、天数、预算，调用大模型生成结构化的行程 JSON 返回。
     *
     * @Valid 触发参数校验（TravelRequestDTO 上的注解），校验不通过会抛 MethodArgumentNotValidException，
     *        由 GlobalExceptionHandler 统一处理为 400 错误。
     * @RequestBody 表示从请求体 JSON 反序列化为 TravelRequestDTO 对象。
     */
    @PostMapping("/recommend")
    public Result<TravelRecommendVO> recommend (@Valid @RequestBody TravelRequestDTO travelRequestDTO,
                                                HttpServletRequest request) {
        // 滑动窗口限流：同一 IP 在窗口期内的请求数超过阈值则拒绝
        if (!rateLimiter.tryAcquire("recommend:" + clientIp(request))) {
            return Result.fail(429, "请求过于频繁，请稍后再试");
        }
        // 调用 Service 层完成实际的大模型调用与结果解析（uid 由 JWT 拦截器注入，用于行程落库）
        Long uid = (Long) request.getAttribute("uid");
        TravelRecommendVO travelRecommendVO = travelService.recommend(travelRequestDTO.getCity(), travelRequestDTO.getDays(), travelRequestDTO.getBudget(), uid);
        return Result.ok(travelRecommendVO);
    }

    /**
     * AI 对话接口（流式）
     * POST /api/travel/chat
     * produces = "text/event-stream" 表示返回 SSE 流式响应，
     * 前端通过 fetch + ReadableStream 逐块读取，实现打字机效果。
     *
     * SseEmitter 是 Spring 提供的服务端推送工具，可在异步线程中持续向前端写数据。
     * sessionId 可选：传入后按会话隔离历史记忆（Redis 存储）。
     */
    @PostMapping(value ="/chat", produces = "text/event-stream")
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO chatRequestDTO,
                           HttpServletRequest request) {
        // 滑动窗口限流：触发时推送错误事件后正常关闭，前端按统一错误格式处理
        if (!rateLimiter.tryAcquire("chat:" + clientIp(request))) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().data(
                        objectMapper.writeValueAsString(StreamErrorVO.of("请求过于频繁，请稍后再试"))));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }
        return travelService.chat(chatRequestDTO.getSessionId(), chatRequestDTO.getMessage());
    }

    /**
     * 知识库热重载接口
     * POST /api/travel/kb/reload
     * 重新扫描 docs/ 目录并向量化入库，用于更新知识库文档后无需重启服务。
     * 受 JWT 拦截器保护，需登录后调用。
     */
    @PostMapping("/kb/reload")
    public Result<Map<String, Object>> reloadKnowledgeBase() {
        int segments = knowledgeBaseInitializer.reload();
        return Result.ok(Map.of("segments", segments, "message", "知识库重载成功"));
    }

    /** 提取客户端真实 IP：优先取代理透传的 X-Forwarded-For，否则用直连地址 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
