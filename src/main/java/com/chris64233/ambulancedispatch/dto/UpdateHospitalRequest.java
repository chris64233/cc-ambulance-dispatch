package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Set;

/**
 * 医院更新上报：可修改接收急救类型、床位容量（不得小于已预留数）与接收状态。
 * 字段为 null 表示不修改该项。
 */
public record UpdateHospitalRequest(
        Set<@NotBlank String> acceptedEmergencyTypes,
        @PositiveOrZero Integer bedCapacity,
        String receivingStatus) {
}
