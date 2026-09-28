package com.chris64233.ambulancedispatch.domain;

/**
 * 改派任务状态。
 * PENDING 等待执行改派；FULFILLED 改派成功，任务关闭；
 * CLOSED 派遣在改派前已完成/取消，任务作废。
 */
public enum DiversionTaskStatus {
    PENDING,
    FULFILLED,
    CLOSED
}
