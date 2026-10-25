package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 限流异常：返回 429 Too Many Requests，并提示客户端稍后重试。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimitException(RateLimitException e) {
        log.warn("请求被限流：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
