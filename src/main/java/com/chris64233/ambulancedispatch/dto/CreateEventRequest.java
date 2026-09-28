package com.chris64233.ambulancedispatch.dto;

import com.chris64233.ambulancedispatch.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record CreateEventRequest(
        @NotBlank String location,
        @NotBlank String serviceArea,
        String emergencyType,
        @NotNull Priority priority,
        Set<@NotBlank String> requiredCapabilities) {
}
