package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Set;

/**
 * 医院上报：可接收的急救类型、床位容量与接收状态。
 */
public record RegisterHospitalRequest(
        @NotBlank String name,
        Set<@NotBlank String> acceptedEmergencyTypes,
        @NotNull @PositiveOrZero Integer bedCapacity,
        String receivingStatus) {
}
