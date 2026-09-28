package com.chris64233.ambulancedispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 医院拒收请求：当前目的医院拒绝接收，记录原因并生成待处理改派任务。
 * 拒收不释放车辆与救护组，也不释放原医院名额（直到成功改往新医院）。
 *
 * @param bizNo 业务号，保证本次拒收幂等
 * @param dispatchId 被拒收的进行中派遣单
 * @param hospitalId 发起拒收的医院（必须是当前目的医院）
 * @param reasonDetail 拒收原因（必填）
 */
public record RejectRequest(
        @NotBlank String bizNo,
        @NotNull Long dispatchId,
        @NotNull Long hospitalId,
        @NotBlank String reasonDetail) {
}
