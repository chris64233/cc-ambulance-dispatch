package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.DiversionTask;
import java.time.Instant;

public record DiversionTaskResponse(
        Long id,
        Long dispatchId,
        Long eventId,
        Long fromHospitalId,
        Long rejectionId,
        String reason,
        String status,
        Instant createdAt,
        Instant fulfilledAt) {

    public static DiversionTaskResponse from(DiversionTask t) {
        return new DiversionTaskResponse(
                t.getId(),
                t.getDispatch().getId(),
                t.getEvent().getId(),
                t.getFromHospital().getId(),
                t.getRejection() == null ? null : t.getRejection().getId(),
                t.getReason().name(),
                t.getStatus().name(),
                t.getCreatedAt(),
                t.getFulfilledAt());
    }
}
