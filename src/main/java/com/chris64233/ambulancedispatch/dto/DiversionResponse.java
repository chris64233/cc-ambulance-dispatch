package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Diversion;
import java.time.Instant;

/**
 * 改派记录视图：包含历次改派与拒收的原因、目标医院、结果。
 */
public record DiversionResponse(
        Long id,
        String bizNo,
        Long dispatchId,
        Long eventId,
        Long fromHospitalId,
        Long toHospitalId,
        String reason,
        String reasonDetail,
        String status,
        String failureCode,
        boolean replayed,
        Instant createdAt,
        Instant finishedAt) {

    public static DiversionResponse from(Diversion d) {
        return from(d, false);
    }

    public static DiversionResponse from(Diversion d, boolean replayed) {
        return new DiversionResponse(
                d.getId(),
                d.getBizNo(),
                d.getDispatch().getId(),
                d.getEvent().getId(),
                d.getFromHospital().getId(),
                d.getToHospital() == null ? null : d.getToHospital().getId(),
                d.getReason().name(),
                d.getReasonDetail(),
                d.getStatus().name(),
                d.getFailureCode(),
                replayed,
                d.getCreatedAt(),
                d.getFinishedAt());
    }
}
