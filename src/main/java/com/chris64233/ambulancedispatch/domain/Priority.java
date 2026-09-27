package com.chris64233.ambulancedispatch.domain;

/**
 * 事件优先级，数值越大优先级越高。
 */
public enum Priority {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    Priority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
