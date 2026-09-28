package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.EmergencyType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * 医院登记请求。
 *
 * @param acceptedEmergencyTypes 可接收的急救类型集合，至少一种
 * @param bedCapacity 床位容量，>= 0
 */
public record RegisterHospitalRequest(
        @NotBlank String name,
        @NotEmpty Set<EmergencyType> acceptedEmergencyTypes,
        @Min(0) int bedCapacity) {
}
