package com.shophub.config;

import com.shophub.dto.Result;
import com.shophub.exception.BusinessException;
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

@Slf4j
@RestControllerAdvice
public class ShopShopExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("[shop-shop] 业务异常: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimit(RateLimitException e) {
        log.warn("[shop-shop] 限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Result> handleRpcException(RpcException e) {
        log.error("[shop-shop] Dubbo RpcException: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("店铺服务内部异常，请稍后重试"));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Result> handleTimeoutException(TimeoutException e) {
        log.error("[shop-shop] Dubbo TimeoutException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Result.fail("店铺服务响应超时，请稍后重试"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result> handleNotFound(NoHandlerFoundException e) {
        log.warn("[shop-shop] 路径未找到: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(e.getRequestURL() + " 不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[shop-shop] 方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(e.getMethod() + " 方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleOther(Exception e) {
        log.error("[shop-shop] 未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("店铺服务内部错误"));
    }
}
