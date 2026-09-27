package com.chris64233.ambulancedispatch.api.error;

import org.springframework.http.HttpStatus;

/**
 * 统一业务错误码，与 HTTP 状态一一对应。
 */
public enum ErrorCode {

    /** 请求体校验失败。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    /** 引用的资源不存在。 */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 同一业务号提交了不同内容。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),

    /** 资源被占用且不满足抢占条件。 */
    RESOURCE_UNAVAILABLE(HttpStatus.CONFLICT),

    /** 当前状态不允许该操作（含终态不可变更）。 */
    ILLEGAL_STATE(HttpStatus.CONFLICT),

    /** 资源不满足事件要求（区域 / 能力）。 */
    REQUIREMENT_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 未预期的服务器错误。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
