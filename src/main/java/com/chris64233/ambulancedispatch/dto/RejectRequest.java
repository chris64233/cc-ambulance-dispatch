package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医院拒收请求：记录拒收原因并生成改派任务，不释放车辆与救护组。
 */
public record RejectRequest(
        @NotBlank String reason) {
}
