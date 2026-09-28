package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 抢占请求：用指定车辆与救护组替换目标派遣，服务新的高优先级事件。
 * 抢占同时原子预留新目的医院名额；目标派遣的原医院名额随原单终止而释放。
 *
 * @param bizNo 业务号，保证本次抢占幂等
 */
public record PreemptRequest(
        @NotBlank String bizNo,
        @NotNull Long newEventId,
        @NotNull Long targetDispatchId,
        @NotNull Long ambulanceId,
        @NotNull Long crewId,
        @NotNull Long hospitalId) {
}
