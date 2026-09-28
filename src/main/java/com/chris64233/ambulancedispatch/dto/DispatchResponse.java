package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Dispatch;
import java.time.Instant;

public record DispatchResponse(
        Long id,
        String bizNo,
        Long eventId,
        Long ambulanceId,
        Long crewId,
        Long hospitalId,
        String status,
        Long preemptedDispatchId,
        Long preemptionRootId,
        Instant dispatchedAt,
        Instant arrivedAt,
        Instant arrivedHospitalAt,
        Instant finishedAt,
        boolean replayed) {

    public static DispatchResponse from(Dispatch d) {
        return from(d, false);
    }

    public static DispatchResponse from(Dispatch d, boolean replayed) {
        return new DispatchResponse(
                d.getId(),
                d.getBizNo(),
                d.getEvent().getId(),
                d.getAmbulance().getId(),
                d.getCrew().getId(),
                d.getDestinationHospital().getId(),
                d.getStatus().name(),
                d.getPreemptedDispatch() == null ? null : d.getPreemptedDispatch().getId(),
                d.getPreemptionRoot().getId(),
                d.getDispatchedAt(),
                d.getArrivedAt(),
                d.getArrivedHospitalAt(),
                d.getFinishedAt(),
                replayed);
    }
}
