package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 联合派遣请求：必须同时指定车辆、救护组与目的医院。
 *
 * @param bizNo 业务号，保证幂等
 */
public record DispatchRequest(
        @NotBlank String bizNo,
        @NotNull Long eventId,
        @NotNull Long ambulanceId,
        @NotNull Long crewId,
        @NotNull Long hospitalId) {
}
