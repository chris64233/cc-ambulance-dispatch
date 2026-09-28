package com.chris64233.ambulancedispatch.web;

import com.chris64233.ambulancedispatch.dto.ErrorResponse;
import com.chris64233.ambulancedispatch.exception.BusinessException;
import com.chris64233.ambulancedispatch.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一错误响应：所有异常都转换为
 * {@code {timestamp, status, error, code, message, path}} 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        return build(code.getHttpStatus(), code.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getCode(),
                "请求的资源不存在: " + request.getRequestURI(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex, HttpServletRequest request) {
        String message;
        if (ex instanceof MethodArgumentNotValidException manv) {
            message = manv.getBindingResult().getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .findFirst()
                    .orElse(ErrorCode.VALIDATION_ERROR.getMessage());
        } else {
            message = ex.getMessage() == null ? ErrorCode.VALIDATION_ERROR.getMessage() : ex.getMessage();
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.getCode(), message, request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.RESOURCE_UNAVAILABLE.getCode(),
                "资源已被并发修改，请重试", request);
    }

    /** 悲观锁等待超时或被选为死锁牺牲者：可安全重试，返回 409 而非 500。 */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handlePessimisticLock(
            org.springframework.dao.PessimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.RESOURCE_UNAVAILABLE.getCode(),
                "资源竞争冲突，请重试", request);
    }

    /** 唯一约束等在提交时才触发的并发写入冲突（如同名医院/车辆/救护组、重复业务号）。 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_RESOURCE.getCode(),
                "数据唯一性冲突，资源可能已被并发创建: " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.getCode(),
                ErrorCode.INTERNAL_ERROR.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
