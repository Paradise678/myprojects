package org.example.traveljavaserver.vo;

import lombok.Data;

/**
 * 统一响应结果包装类
 * 所有接口都返回 Result<T> 结构，前端通过统一的格式解析响应。
 *
 * 结构说明：
 *   success  - 业务是否成功（与 HTTP 状态码区分，HTTP 200 也可能业务失败）
 *   code     - 业务状态码（200 成功，500 失败，400 参数错误）
 *   message  - 提示信息
 *   data     - 业务数据（泛型 T）
 *   error    - 错误详情（失败时填充）
 *   rawResponse - 原始响应（调试用，如大模型返回的未解析文本）
 */
@Data
public class Result<T> {
    private Boolean success;
    private Integer code;
    private String message;
    private T data;
    private String error;
    private String rawResponse;

    /** 成功响应（无数据） */
    public static <T> Result<T> ok() {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode(200);
        result.setMessage("成功");
        return result;
    }

    /** 成功响应（携带数据） */
    public static <T> Result<T> ok(T data){
        Result<T> result = ok();
        result.setData(data);
        return result;
    }

    /** 失败响应（默认 500） */
    public static <T> Result<T> fail() {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(500);
        result.setMessage("失败");
        return result;
    }

    /** 失败响应（自定义状态码和信息） */
    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = fail();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    /** 错误响应（携带错误详情和原始响应，便于前端排查） */
    public static <T> Result<T> error(String error, String rawResponse) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setError(error);
        result.setRawResponse(rawResponse);
        return result;
    }
}
