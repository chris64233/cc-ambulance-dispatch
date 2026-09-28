package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.HospitalRejection;
import java.time.Instant;

public record RejectionResponse(
        Long id,
        Long hospitalId,
        String hospitalName,
        Long dispatchId,
        Long eventId,
        Long reservationId,
        String reason,
        Instant rejectedAt,
        Long diversionTaskId) {

    public static RejectionResponse of(HospitalRejection r, Long diversionTaskId) {
        return new RejectionResponse(
                r.getId(),
                r.getHospital().getId(),
                r.getHospital().getName(),
                r.getDispatch().getId(),
                r.getEvent().getId(),
                r.getReservation().getId(),
                r.getReason(),
                r.getRejectedAt(),
                diversionTaskId);
    }
}
