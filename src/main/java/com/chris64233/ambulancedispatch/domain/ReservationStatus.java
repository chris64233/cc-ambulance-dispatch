package com.chris64233.ambulancedispatch.domain;

/**
 * 医院名额预留状态。
 * HELD 名额占用中（随派遣生效）；RELEASED 随派遣完成/取消/抢占或改派成功而释放；
 * REJECTED 医院拒收（名额归还但保留拒收原因，历史记录不再占用床位）。
 */
public enum ReservationStatus {
    HELD,
    RELEASED,
    REJECTED
}
