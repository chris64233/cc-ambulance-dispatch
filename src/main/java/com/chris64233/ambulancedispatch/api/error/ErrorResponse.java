package com.chris64233.ambulancedispatch.api.error;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(ErrorCode code, String message, String path) {
        return new ErrorResponse(Instant.now(), code.getStatus().value(), code.name(), message, path, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), code.getStatus().value(), code.name(), message, path, fieldErrors);
    }
}
