package com.shophub.exception;

/**
 * 业务层自定义异常（可带 HTTP 状态码）。
 *
 * <p>适用场景：业务校验失败、资源不存在、权限不足等「预期内」的异常。
 * 与 {@link DubboRemoteException}（跨服务 RPC 异常）不同，
 * BusinessException 只在单个微服务内部抛出。</p>
 */
public class BusinessException extends RuntimeException {

    /** 默认 400 Bad Request */
    private final int httpStatus;

    public BusinessException(String message) {
        super(message);
        this.httpStatus = 400;
    }

    public BusinessException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 400;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
