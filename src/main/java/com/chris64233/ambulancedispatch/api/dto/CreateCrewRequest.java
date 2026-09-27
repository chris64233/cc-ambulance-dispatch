package com.chris64233.ambulancedispatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * 救护组登记请求。
 */
public record CreateCrewRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotEmpty(message = "qualifications 不能为空") Set<String> qualifications) {
}
