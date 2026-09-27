package com.chris64233.ambulancedispatch.domain;

/**
 * 事件优先级，ordinal 越大优先级越高。
 * 只有严格更高的优先级才允许抢占。
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH
}
