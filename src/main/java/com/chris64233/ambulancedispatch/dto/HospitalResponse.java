package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Hospital;
import java.util.SortedSet;
import java.util.TreeSet;

public record HospitalResponse(
        Long id,
        String name,
        SortedSet<String> acceptedEmergencyTypes,
        int bedCapacity,
        int reservedBeds,
        int availableBeds,
        String receivingStatus) {

    public static HospitalResponse from(Hospital h) {
        return new HospitalResponse(
                h.getId(),
                h.getName(),
                new TreeSet<>(h.getAcceptedEmergencyTypes()),
                h.getBedCapacity(),
                h.getReservedBeds(),
                h.getBedCapacity() - h.getReservedBeds(),
                h.getReceivingStatus().name());
    }
}
