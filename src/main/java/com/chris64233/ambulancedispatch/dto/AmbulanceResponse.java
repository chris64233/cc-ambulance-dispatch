package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Ambulance;
import java.util.SortedSet;
import java.util.TreeSet;

public record AmbulanceResponse(
        Long id,
        String plateNumber,
        SortedSet<String> serviceAreas,
        SortedSet<String> equipmentCapabilities,
        String status) {

    public static AmbulanceResponse from(Ambulance a) {
        return new AmbulanceResponse(a.getId(), a.getPlateNumber(),
                new TreeSet<>(a.getServiceAreas()),
                new TreeSet<>(a.getEquipmentCapabilities()),
                a.getStatus().name());
    }
}
