package com.chris64233.ambulancedispatch.domain;

/**
 * 急救类型：事件的救治类别，医院按类别上报可接收范围。
 */
public enum EmergencyType {
    /** 创伤。 */
    TRAUMA,
    /** 心脑血管。 */
    CARDIAC,
    /** 中毒。 */
    POISONING,
    /** 儿科。 */
    PEDIATRIC,
    /** 普通急救。 */
    GENERAL
}
