package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

public record RegisterCrewRequest(
        @NotBlank String name,
        Set<@NotBlank String> qualifications,
        Boolean onDuty) {
}
