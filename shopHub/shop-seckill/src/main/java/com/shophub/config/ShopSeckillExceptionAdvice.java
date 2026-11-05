package com.shophub.config;

import com.shophub.dto.Result;
import com.shophub.exception.BusinessException;
import com.shophub.exception.DubboRemoteException;
import com.shophub.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * shop-seckill 全局异常处理。
 *
 * <p>秒杀服务是唯一同时作为 Consumer（远程调 shop-pay）和 Provider（对外暴露下单接口）的模块，
 * 需要处理两类 Dubbo 异常：</p>
 * <ul>
 *   <li>**Consumer 侧**（DubboRemoteException）：远程调 shop-pay 失败，
 *       返回 503 Service Unavailable + "支付服务暂时不可用"</li>
 *   <li>**Provider 侧**（RpcException/TimeoutException）：被其他服务远程调用时的内部异常</li>
 * </ul>
 *
 * <p>另外秒杀特有：</p>
 * <ul>
 *   <li>RateLimitException：滑动窗口限流，返回 429 + Retry-After</li>
 *   <li>BusinessException：库存不足、重复下单等业务场景</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class ShopSeckillExceptionAdvice {

    // ==================== 业务层异常 ====================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("[shop-seckill] 业务异常: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimit(RateLimitException e) {
        log.warn("[shop-seckill] 限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(Result.fail(e.getMessage()));
    }

    // ==================== Dubbo Consumer 侧异常 ====================

    /**
     * Consumer 包装过的远程异常 —— 带目标服务名，日志可定位。
     */
    @ExceptionHandler(DubboRemoteException.class)
    public ResponseEntity<Result> handleDubboRemote(DubboRemoteException e) {
        log.error("[shop-seckill] Dubbo 远程调用失败 → targetService={}, msg={}, cause={}",
                e.getTargetService(), e.getMessage(),
                e.getCause() != null ? e.getCause().getMessage() : "null", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(e.getMessage()));
    }

    /**
     * Consumer 侧未包装的原始 RpcException（超时、Provider 不可用等）。
     * 秒杀场景返回友好提示让用户重试。
     */
    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Result> handleRpcException(RpcException e) {
        log.error("[shop-seckill] Dubbo RpcException: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail("支付服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Result> handleTimeoutException(TimeoutException e) {
        log.error("[shop-seckill] Dubbo TimeoutException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Result.fail("服务响应超时，请稍后重试"));
    }

    // ==================== 404 / 405 ====================

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result> handleNotFound(NoHandlerFoundException e) {
        log.warn("[shop-seckill] 路径未找到: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail("接口不存在: " + e.getRequestURL()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[shop-seckill] 方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail("不支持的请求方法: " + e.getMethod()));
    }

    // ==================== 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleOther(Exception e) {
        log.error("[shop-seckill] 未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("服务器内部错误，请稍后重试"));
    }
}
