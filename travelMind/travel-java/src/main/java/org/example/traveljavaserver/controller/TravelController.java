package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.ChatRequestDTO;
import org.example.traveljavaserver.service.TravelService;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.web.bind.annotation.*;
import org.example.traveljavaserver.vo.Result;
import org.example.traveljavaserver.dto.TravelRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 旅游相关接口控制器
 * 所有接口统一前缀：/api/travel
 *
 * 提供两个核心能力：
 *   1. POST /recommend —— 根据城市/预算/天数，调用大模型生成完整行程规划（一次性返回 JSON）
 *   2. POST /chat      —— 与大模型进行流式对话（SSE，逐字返回）
 */
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor   // Lombok 注解：自动为 final 字段生成构造器，实现依赖注入
public class TravelController {
    private final TravelService travelService;

    /**
     * 健康检查接口：用于验证服务是否正常启动
     * GET /api/travel/hello → 返回 "hello world"
     */
    @GetMapping("/hello")
    public Result<String> hello () {
        return  Result.ok("hello world");
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
    public Result<TravelRecommendVO> recommend (@Valid @RequestBody TravelRequestDTO travelRequestDTO) {

        System.out.println(travelRequestDTO.getCity());
        System.out.println(travelRequestDTO.getDays());
        System.out.println(travelRequestDTO.getBudget());
        // 调用 Service 层完成实际的大模型调用与结果解析
        TravelRecommendVO travelRecommendVO = travelService.recommend(travelRequestDTO.getCity(), travelRequestDTO.getDays(), travelRequestDTO.getBudget());
        return Result.ok(travelRecommendVO);
    }

    /**
     * AI 对话接口（流式）
     * POST /api/travel/chat
     * produces = "text/event-stream" 表示返回 SSE 流式响应，
     * 前端通过 fetch + ReadableStream 逐块读取，实现打字机效果。
     *
     * SseEmitter 是 Spring 提供的服务端推送工具，可在异步线程中持续向前端写数据。
     */
    @PostMapping(value ="/chat", produces = "text/event-stream")
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO chatRequestDTO) {
        return travelService.chat(chatRequestDTO.getMessage());
    }
}
