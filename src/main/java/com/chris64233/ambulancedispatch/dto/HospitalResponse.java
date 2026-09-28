package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Hospital;
import java.util.SortedSet;
import java.util.TreeSet;

public record HospitalResponse(
        Long id,
        String name,
        SortedSet<String> acceptedEmergencyTypes,
        int bedCapacity,
        int reservedCount,
        int availableBeds,
        String receivingStatus) {

    public static HospitalResponse from(Hospital h) {
        return new HospitalResponse(
                h.getId(),
                h.getName(),
                h.getAcceptedEmergencyTypes().stream().map(Enum::name)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                h.getBedCapacity(),
                h.getReservedCount(),
                Math.max(0, h.getBedCapacity() - h.getReservedCount()),
                h.getReceivingStatus().name());
    }
}
