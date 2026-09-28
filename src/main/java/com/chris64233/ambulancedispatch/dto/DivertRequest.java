package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 途中改派请求：救护车到达现场后，因医院关闭或病情变化改往新医院。
 *
 * @param bizNo 业务号，保证本次改派幂等
 * @param dispatchId 要改派的进行中派遣单
 * @param fromHospitalId 原目的医院，用于识别过期请求（与当前目的地不一致则拒绝）
 * @param toHospitalId 申请改往的新医院
 * @param reason HOSPITAL_CLOSED / CONDITION_CHANGED
 * @param reasonDetail 补充说明
 */
public record DivertRequest(
        @NotBlank String bizNo,
        @NotNull Long dispatchId,
        @NotNull Long fromHospitalId,
        @NotNull Long toHospitalId,
        @NotBlank String reason,
        String reasonDetail) {
}
