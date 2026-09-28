package com.chris64233.ambulancedispatch.domain;

/**
 * 改派原因。
 * HOSPITAL_CLOSED 医院关闭接收；CONDITION_CHANGED 患者病情变化；HOSPITAL_REJECTED 医院拒收。
 */
public enum DiversionReason {
    HOSPITAL_CLOSED,
    CONDITION_CHANGED,
    HOSPITAL_REJECTED
}
