package com.chris64233.ambulancedispatch.domain;

/**
 * 派遣单状态。
 * ACTIVE 执行中（含已到场）；PREEMPTED 被更高优先级事件抢占；
 * COMPLETED 完成（终态）；CANCELLED 取消（终态）。
 */
public enum DispatchStatus {
    ACTIVE,
    PREEMPTED,
    COMPLETED,
    CANCELLED
}
