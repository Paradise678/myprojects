package com.hmdp.exception;

/**
 * 触发限流时抛出，由全局异常处理器转换为 HTTP 429。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
