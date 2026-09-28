package com.chris64233.ambulancedispatch.domain;

/**
 * 医院接收状态。
 * OPEN 正常接收（可向其派遣/改派）；CLOSED 关闭接收，不得收到新事件。
 */
public enum ReceivingStatus {
    OPEN,
    CLOSED
}
