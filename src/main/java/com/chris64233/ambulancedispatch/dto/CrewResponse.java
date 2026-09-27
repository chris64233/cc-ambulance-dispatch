package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Crew;
import java.util.SortedSet;
import java.util.TreeSet;

public record CrewResponse(
        Long id,
        String name,
        SortedSet<String> qualifications,
        String dutyStatus,
        String assignmentStatus) {

    public static CrewResponse from(Crew c) {
        return new CrewResponse(c.getId(), c.getName(),
                new TreeSet<>(c.getQualifications()),
                c.getDutyStatus().name(),
                c.getAssignmentStatus().name());
    }
}
