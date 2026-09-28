package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.EmergencyEvent;
import java.time.Instant;
import java.util.SortedSet;
import java.util.TreeSet;

public record EventResponse(
        Long id,
        String location,
        String serviceArea,
        String priority,
        String emergencyType,
        SortedSet<String> requiredCapabilities,
        String status,
        Instant createdAt) {

    public static EventResponse from(EmergencyEvent e) {
        return new EventResponse(e.getId(), e.getLocation(), e.getServiceArea(),
                e.getPriority().name(),
                e.getEmergencyType().name(),
                new TreeSet<>(e.getRequiredCapabilities()),
                e.getStatus().name(),
                e.getCreatedAt());
    }
}
