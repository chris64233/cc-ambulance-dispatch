package com.chris64233.ambulancedispatch.domain;

/**
 * 车辆状态。
 * AVAILABLE 可派遣；DISPATCHED 已被派遣占用；OUT_OF_SERVICE 停用。
 */
public enum VehicleStatus {
    AVAILABLE,
    DISPATCHED,
    OUT_OF_SERVICE
}
