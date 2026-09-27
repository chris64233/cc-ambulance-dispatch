package com.chris64233.ambulancedispatch.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码与 HTTP 状态映射。统一错误响应 {@code {timestamp, code, message, path}}。
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "事件不存在"),
    AMBULANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "AMBULANCE_NOT_FOUND", "车辆不存在"),
    CREW_NOT_FOUND(HttpStatus.NOT_FOUND, "CREW_NOT_FOUND", "救护组不存在"),
    DISPATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "DISPATCH_NOT_FOUND", "派遣单不存在"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "资源编号已存在"),
    PREEMPTION_RESOURCE_MISMATCH(HttpStatus.CONFLICT, "PREEMPTION_RESOURCE_MISMATCH",
            "抢占必须接管目标派遣原有的车辆与救护组"),
    IDEMPOTENT_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENT_CONFLICT",
            "相同业务号但请求内容不同"),
    EVENT_NOT_PENDING(HttpStatus.CONFLICT, "EVENT_NOT_PENDING", "事件当前不是待派遣状态"),
    EVENT_TERMINAL(HttpStatus.CONFLICT, "EVENT_TERMINAL", "事件已完成或取消，不可修改"),
    EVENT_NOT_DISPATCHED(HttpStatus.CONFLICT, "EVENT_NOT_DISPATCHED", "事件当前没有进行中的派遣"),
    DISPATCH_NOT_EN_ROUTE(HttpStatus.CONFLICT, "DISPATCH_NOT_EN_ROUTE", "派遣单当前不在途"),
    DISPATCH_ALREADY_ARRIVED(HttpStatus.CONFLICT, "DISPATCH_ALREADY_ARRIVED", "派遣单已到达现场"),
    DISPATCH_TERMINAL(HttpStatus.CONFLICT, "DISPATCH_TERMINAL", "派遣单已完成或取消，不可修改"),
    DISPATCH_IMMUTABLE(HttpStatus.CONFLICT, "DISPATCH_IMMUTABLE", "派遣单不可修改"),
    CAPABILITY_MISMATCH(HttpStatus.CONFLICT, "CAPABILITY_MISMATCH", "车辆设备或人员资质不满足事件要求"),
    SERVICE_AREA_MISMATCH(HttpStatus.CONFLICT, "SERVICE_AREA_MISMATCH", "车辆不服务事件所在区域"),
    CREW_OFF_DUTY(HttpStatus.CONFLICT, "CREW_OFF_DUTY", "救护组当前不值勤"),
    RESOURCE_UNAVAILABLE(HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "车辆或救护组已被占用"),
    PREEMPTION_NOT_HIGHER_PRIORITY(HttpStatus.CONFLICT, "PREEMPTION_NOT_HIGHER_PRIORITY",
            "只有更高优先级事件可以抢占"),
    PREEMPTION_TARGET_NOT_ACTIVE(HttpStatus.CONFLICT, "PREEMPTION_TARGET_NOT_ACTIVE",
            "目标派遣单不在进行中"),
    DISPATCH_ALREADY_ACTIVE(HttpStatus.CONFLICT, "DISPATCH_ALREADY_ACTIVE",
            "事件已有进行中的派遣"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统内部错误");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
