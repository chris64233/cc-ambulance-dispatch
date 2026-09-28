package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 联合派遣请求：必须同时指定车辆、救护组与目的医院。
 * 派遣确认时原子占用车辆、救护组并预留医院名额，任一不足都不形成派遣。
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
