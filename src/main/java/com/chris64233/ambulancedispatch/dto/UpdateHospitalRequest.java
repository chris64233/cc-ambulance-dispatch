package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.EmergencyType;
import com.chris64233.ambulancedispatch.domain.ReceivingStatus;
import jakarta.validation.constraints.Min;
import java.util.Set;

/**
 * 医院信息更新请求：字段为空表示该项不修改（部分更新）。
 * 关闭接收（status=CLOSED）后不再接收新事件，已有名额保持占用。
 */
public record UpdateHospitalRequest(
        Set<EmergencyType> acceptedEmergencyTypes,
        @Min(0) Integer bedCapacity,
        ReceivingStatus receivingStatus) {
}
