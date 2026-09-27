package com.chris64233.ambulancedispatch.api.dto;

import com.chris64233.ambulancedispatch.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * 事件上报请求。
 */
public record CreateIncidentRequest(
        @NotBlank(message = "area 不能为空") String area,
        @NotBlank(message = "location 不能为空") String location,
        @NotNull(message = "priority 不能为空") Priority priority,
        @NotEmpty(message = "requiredCapabilities 不能为空") Set<String> requiredCapabilities) {
}
