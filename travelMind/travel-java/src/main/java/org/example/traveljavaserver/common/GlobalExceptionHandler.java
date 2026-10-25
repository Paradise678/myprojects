package org.example.traveljavaserver.common;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.FieldError;

import java.util.stream.Collectors;

import org.example.traveljavaserver.vo.Result;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * 全局异常处理器
 *
 * @RestControllerAdvice 会拦截所有 Controller 抛出的异常，
 * 在这里统一处理，避免异常直接抛给前端导致 500 错误和堆栈信息泄露。
 *
 * 目前只处理参数校验异常（@Valid 触发的），其他异常可按需扩展。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 处理参数校验异常：当 @Valid 校验不通过时（如城市为空、天数超范围），
     * Spring 会抛出 MethodArgumentNotValidException，这里把所有字段错误信息收集起来，
     * 以 Result.fail(400, message) 的形式返回给前端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)  // 处理参数校验异常
    public Result<Void> handlerException(MethodArgumentNotValidException e) {
        // 把所有字段校验错误的默认提示拼成一个字符串
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(400, message);
    }
}
