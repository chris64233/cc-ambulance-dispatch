package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.HospitalReservation;
import java.time.Instant;

public record ReservationResponse(
        Long id,
        Long hospitalId,
        String hospitalName,
        Long dispatchId,
        Long eventId,
        String emergencyType,
        String status,
        Instant reservedAt,
        Instant releasedAt) {

    public static ReservationResponse from(HospitalReservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getHospital().getId(),
                r.getHospital().getName(),
                r.getDispatch().getId(),
                r.getEvent().getId(),
                r.getEmergencyType().name(),
                r.getStatus().name(),
                r.getReservedAt(),
                r.getReleasedAt());
    }
}
