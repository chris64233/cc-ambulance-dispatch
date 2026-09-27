package com.chris64233.ambulancedispatch.api.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：所有错误统一为 {@link ErrorResponse} 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, ex.getMessage(), request.getRequestURI()));
    }

    /** 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, "请求参数校验失败", request.getRequestURI(), fieldErrors));
    }

    /** 请求体无法解析（如非法枚举值、JSON 语法错误）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, "请求体无法解析: " + ex.getMessage(), request.getRequestURI()));
    }

    /** 唯一约束冲突（如并发提交相同业务号、重复呼号）。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        ErrorCode code = ErrorCode.IDEMPOTENCY_CONFLICT;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, "数据唯一性冲突，可能是重复提交", request.getRequestURI()));
    }

    /** 悲观锁获取失败（并发争抢超时），客户端可重试。 */
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<ErrorResponse> handleLockFailure(Exception ex, HttpServletRequest request) {
        ErrorCode code = ErrorCode.RESOURCE_UNAVAILABLE;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, "资源正被并发操作占用，请重试", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期的错误: {}", request.getRequestURI(), ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, "服务器内部错误", request.getRequestURI()));
    }
}
