package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.DiversionReason;
import com.chris64233.ambulancedispatch.domain.EmergencyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 途中改派请求。
 *
 * <p>{@code fromHospitalId} 不需要客户端传入，服务端以派遣当前目的地为准，防止伪造来源。
 *
 * @param reason 改派原因：HOSPITAL_CLOSED / CONDITION_CHANGED / REJECTED
 * @param emergencyType 本次改派按何急救类型匹配目标医院；为空时使用事件原始类型，
 *                      病情变化（CONDITION_CHANGED）时应传入变化后的类型
 * @param expectedHospitalId 乐观守卫：调用方认为当前应处的目的医院；非空且与当前目的地
 *                           不一致时返回 DIVERSION_DESTINATION_CONFLICT，旧请求不会覆盖新目的地
 */
public record DiversionRequest(
        @NotBlank String bizNo,
        @NotNull Long dispatchId,
        @NotNull Long toHospitalId,
        @NotNull DiversionReason reason,
        EmergencyType emergencyType,
        Long expectedHospitalId) {
}
