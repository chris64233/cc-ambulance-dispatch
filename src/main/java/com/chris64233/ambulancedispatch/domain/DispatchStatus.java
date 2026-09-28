package com.chris64233.ambulancedispatch.domain;

/**
 * 派遣单状态。
 * EN_ROUTE 在途（可被高优先级事件抢占）；ON_SCENE 已到达现场（不可抢占，可拒收/改派）；
 * AT_HOSPITAL 已到达医院（终态前状态，不可再改派）；
 * COMPLETED/CANCELLED 终态；PREEMPTED 被抢占（原事件回到待派遣）。
 */
public enum DispatchStatus {
    EN_ROUTE,
    ON_SCENE,
    AT_HOSPITAL,
    COMPLETED,
    CANCELLED,
    PREEMPTED
}
