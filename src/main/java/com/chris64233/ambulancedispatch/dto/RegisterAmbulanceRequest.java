package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record RegisterAmbulanceRequest(
        @NotBlank String plateNumber,
        @NotEmpty Set<@NotBlank String> serviceAreas,
        @NotEmpty Set<@NotBlank String> equipmentCapabilities) {
}
