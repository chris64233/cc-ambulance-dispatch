package com.chris64233.ambulancedispatch.domain;

/**
 * 救护组值勤状态。
 * ON_DUTY 值勤可派遣；DISPATCHED 已被派遣占用；OFF_DUTY 休班。
 */
public enum DutyStatus {
    ON_DUTY,
    DISPATCHED,
    OFF_DUTY
}
