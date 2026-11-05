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

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * shop-pay 全局异常处理。
 *
 * <p>shop-pay 作为支付核心服务，异常分三类：</p>
 * <ul>
 *   <li>**业务异常**（BusinessException）：参数校验失败、支付状态非法等，HTTP 400</li>
 *   <li>**Dubbo Provider 异常**（RpcException/TimeoutException）：shop-seckill 远程调过来
 *       本服务时 Provider 内部异常，返回 500 + 友好提示</li>
 *   <li>**兜底**：所有其他未知异常返回 500</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class ShopPayExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("[shop-pay] 业务异常: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimit(RateLimitException e) {
        log.warn("[shop-pay] 限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(Result.fail(e.getMessage()));
    }

    /**
     * Dubbo Provider 端被调用时抛出的异常。
     * RpcException 可能出现在 Provider 内部逻辑中（比如嵌套调用超时）。
     */
    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Result> handleRpcException(RpcException e) {
        log.error("[shop-pay] Dubbo RpcException: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("支付服务内部异常，请稍后重试"));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Result> handleTimeoutException(TimeoutException e) {
        log.error("[shop-pay] Dubbo TimeoutException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Result.fail("支付服务响应超时，请稍后重试"));
    }

    /**
     * 支付回调是敏感接口，404/405 都要明确返回，不要落入 500 兜底。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result> handleNotFound(NoHandlerFoundException e) {
        log.warn("[shop-pay] 路径未找到: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail("接口不存在: " + e.getRequestURL()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[shop-pay] 方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail("不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleOther(Exception e) {
        log.error("[shop-pay] 未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("支付服务内部错误"));
    }
}
