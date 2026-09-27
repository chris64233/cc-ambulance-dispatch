package com.chris64233.ambulancedispatch.domain;

/**
 * 事件状态机：
 * PENDING 待派遣 → DISPATCHED 已派遣 → ON_SCENE 已到场 → COMPLETED 完成（终态）。
 * 任意非终态可 → CANCELLED 取消（终态）。
 * 被抢占的派遣会使事件回到 PENDING。
 * 终态（COMPLETED/CANCELLED）不可再变更。
 */
public enum IncidentStatus {
    PENDING,
    DISPATCHED,
    ON_SCENE,
    COMPLETED,
    CANCELLED
}
