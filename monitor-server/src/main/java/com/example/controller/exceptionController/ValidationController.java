package com.example.controller.exceptionController;

import com.example.entity.RestBean;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class ValidationController {

    /**
     * 处理通用参数校验异常，统一返回 400 提示。
     *
     * @param exception 校验异常
     * @return 统一响应体
     */
    @ExceptionHandler(ValidationException.class)
    public RestBean<Void> validateError(ValidationException exception) {
        log.warn("Resolved [{}: {}]", exception.getClass().getName(), exception.getMessage());
        return RestBean.failure(400, "请求参数有误");
    }

    /**
     * 处理请求体字段校验失败，拼接字段错误信息返回给调用方。
     *
     * @param exception 参数校验异常
     * @return 统一响应体
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public RestBean<Void> methodArgumentNotValid(MethodArgumentNotValidException exception) {
        log.warn("参数校验失败: {}", exception.getMessage());
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("请求参数有误");
        return RestBean.failure(400, message);
    }

    /**
     * 处理请求体结构或 JSON 格式错误。
     *
     * @param exception 请求体解析异常
     * @return 统一响应体
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public RestBean<Void> httpMessageNotReadable(HttpMessageNotReadableException exception) {
        log.warn("请求体解析失败: {}", exception.getMessage());
        return RestBean.failure(400, "请求体格式错误");
    }

    /**
     * 处理查询参数类型转换失败，例如 ISO 时间字符串格式错误。
     *
     * @param exception 参数类型转换异常
     * @return 统一响应体
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public RestBean<Void> methodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("请求参数类型错误: name={}, value={}, targetType={}",
                exception.getName(), exception.getValue(), exception.getRequiredType());
        return RestBean.failure(400, "请求参数有误");
    }

    /**
     * 保留框架抛出的状态码语义，避免统一兜底时覆盖 4xx/5xx 语义。
     *
     * @param exception 状态异常
     * @return 统一响应体
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RestBean<Void>> handleResponseStatusException(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String message = exception.getReason();
        RestBean<Void> body = RestBean.failure(status, message == null || message.isBlank() ? "请求失败" : message);
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }

    /**
     * 处理未被显式捕获的其他异常，返回通用 500 响应。
     *
     * @param exception 未处理异常
     * @return 统一响应体
     */
    @ExceptionHandler(Exception.class)
    public RestBean<Void> handleException(Exception exception) {
        log.error("未处理的异常", exception);
        return RestBean.failure(500, "服务器内部错误");
    }
}
