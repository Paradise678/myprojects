package com.shophub.exception;

import lombok.Getter;

/**
 * Dubbo 跨服务调用异常包装器。
 *
 * <p>当微服务通过 @DubboReference 远程调用其他 Provider 时，
 * 如果目标服务不可用、超时、或 Provider 内部抛了业务异常，
 * Dubbo 会抛 RpcException / TimeoutException。
 * 本类把它包装成 Result.fail() 友好格式，避免直接把 Dubbo 内部堆栈暴露给前端。</p>
 */
@Getter
public class DubboRemoteException extends RuntimeException {

    /** 目标服务名（如 "shop-pay"），便于日志定位 */
    private final String targetService;

    public DubboRemoteException(String message) {
        super(message);
        this.targetService = "unknown";
    }

    public DubboRemoteException(String message, String targetService) {
        super(message);
        this.targetService = targetService;
    }

    public DubboRemoteException(String message, Throwable cause) {
        super(message, cause);
        this.targetService = extractServiceName(cause);
    }

    private static String extractServiceName(Throwable cause) {
        if (cause == null) return "unknown";
        String msg = cause.getMessage();
        if (msg == null) return cause.getClass().getSimpleName();
        // Dubbo RpcException 消息格式："Failed to invoke remote method: createPay, provider: dubbo://...?application=shop-pay"
        int idx = msg.indexOf("application=");
        if (idx > 0) {
            int end = msg.indexOf('&', idx);
            if (end < 0) end = msg.length();
            return msg.substring(idx + 12, end);
        }
        return cause.getClass().getSimpleName();
    }
}
