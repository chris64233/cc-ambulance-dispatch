package com.chris64233.ambulancedispatch.domain;

/**
 * 医院接收状态。
 * OPEN 正常接收；CLOSED 关闭接收（已有名额保持占用，但不能再收到新事件，也不能作为改派目标）。
 */
public enum ReceivingStatus {
    OPEN,
    CLOSED
}
