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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * shop-user 全局异常处理。
 *
 * <p>shop-user 负责用户认证（登录/验证码）和用户信息查询：</p>
 * <ul>
 *   <li>业务异常：手机号格式错误、验证码无效、用户不存在</li>
 *   <li>Dubbo Provider 异常：被其他微服务远程调用户信息时</li>
 *   <li>兜底 500</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class ShopUserExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("[shop-user] 业务异常: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimit(RateLimitException e) {
        log.warn("[shop-user] 限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Result> handleRpcException(RpcException e) {
        log.error("[shop-user] Dubbo RpcException: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("用户服务内部异常，请稍后重试"));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Result> handleTimeoutException(TimeoutException e) {
        log.error("[shop-user] Dubbo TimeoutException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Result.fail("用户服务响应超时，请稍后重试"));
    }

    /**
     * 请求路径无匹配（404）。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result> handleNotFound(NoHandlerFoundException e) {
        log.warn("[shop-user] 路径未找到: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail("接口不存在: " + e.getRequestURL()));
    }

    /**
     * HTTP 方法不支持（405）。典型场景：GET 请求打到只支持 POST 的接口上。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[shop-user] 方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail("不支持的请求方法: " + e.getMethod()));
    }

    /**
     * 缺少必填请求参数（400）。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[shop-user] 缺少参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleOther(Exception e) {
        log.error("[shop-user] 未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("用户服务内部错误"));
    }
}
