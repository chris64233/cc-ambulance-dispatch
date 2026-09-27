package com.chris64233.ambulancedispatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * 车辆登记请求。
 */
public record CreateVehicleRequest(
        @NotBlank(message = "callSign 不能为空") String callSign,
        @NotBlank(message = "serviceArea 不能为空") String serviceArea,
        @NotEmpty(message = "equipment 不能为空") Set<String> equipment) {
}
