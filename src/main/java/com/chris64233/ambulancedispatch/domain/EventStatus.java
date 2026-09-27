package com.chris64233.ambulancedispatch.domain;

/**
 * 事件生命周期状态。
 * PENDING 待派遣；DISPATCHED 已派遣（资源在途/在场）；COMPLETED/CANCELLED 为不可修改终态。
 */
public enum EventStatus {
    PENDING,
    DISPATCHED,
    COMPLETED,
    CANCELLED
}
