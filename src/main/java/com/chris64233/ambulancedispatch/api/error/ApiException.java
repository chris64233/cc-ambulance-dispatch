package com.chris64233.ambulancedispatch.api.error;

/**
 * 业务异常：携带统一错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + " 不存在: id=" + id);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(ErrorCode.RESOURCE_UNAVAILABLE, message);
    }

    public static ApiException illegalState(String message) {
        return new ApiException(ErrorCode.ILLEGAL_STATE, message);
    }

    public static ApiException requirementNotMet(String message) {
        return new ApiException(ErrorCode.REQUIREMENT_NOT_MET, message);
    }
}
