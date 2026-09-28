package com.chris64233.ambulancedispatch.domain;

/**
 * 改派触发原因。
 */
public enum DiversionReason {
    /** 医院关闭接收。 */
    HOSPITAL_CLOSED,
    /** 患者病情变化（所需救治类型改变）。 */
    CONDITION_CHANGED,
    /** 医院拒收后改派。 */
    REJECTED
}
