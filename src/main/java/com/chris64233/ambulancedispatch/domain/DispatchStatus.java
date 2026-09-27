package com.chris64233.ambulancedispatch.domain;

/**
 * 派遣单状态。
 * EN_ROUTE 在途（可被高优先级事件抢占）；ON_SCENE 已到达现场（不可被抢占）；
 * COMPLETED/CANCELLED 终态；PREEMPTED 被抢占（原事件回到待派遣）。
 */
public enum DispatchStatus {
    EN_ROUTE,
    ON_SCENE,
    COMPLETED,
    CANCELLED,
    PREEMPTED
}
