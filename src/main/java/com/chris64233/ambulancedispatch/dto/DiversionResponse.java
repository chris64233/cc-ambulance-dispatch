package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.DiversionRecord;
import java.time.Instant;

/**
 * 改派结果：成功时原名额已释放、目的地已切换；失败时原目的地与派遣保持不变。
 */
public record DiversionResponse(
        Long diversionId,
        String bizNo,
        Long dispatchId,
        Long eventId,
        Long fromHospitalId,
        Long toHospitalId,
        String reason,
        String emergencyType,
        String status,
        String failMessage,
        Instant createdAt,
        boolean replayed) {

    public static DiversionResponse from(DiversionRecord r) {
        return from(r, false);
    }

    public static DiversionResponse from(DiversionRecord r, boolean replayed) {
        return new DiversionResponse(
                r.getId(),
                r.getBizNo(),
                r.getDispatch().getId(),
                r.getEvent().getId(),
                r.getFromHospital().getId(),
                r.getToHospital().getId(),
                r.getReason().name(),
                r.getEmergencyType().name(),
                r.getStatus().name(),
                r.getFailMessage(),
                r.getCreatedAt(),
                replayed);
    }
}
