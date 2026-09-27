package com.chris64233.ambulancedispatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 派遣请求。requestId 为派遣业务号（幂等键）。
 */
public record CreateDispatchRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "incidentId 不能为空") Long incidentId,
        @NotNull(message = "vehicleId 不能为空") Long vehicleId,
        @NotNull(message = "crewId 不能为空") Long crewId) {
}
