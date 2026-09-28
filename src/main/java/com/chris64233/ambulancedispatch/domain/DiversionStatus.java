package com.chris64233.ambulancedispatch.domain;

/**
 * 改派任务状态。
 * PENDING 医院拒收后生成，等待执行改派；SUCCESS 新医院预留成功，目的地已切换；
 * FAILED 新医院预留失败，原目的地与派遣保持不变；RESOLVED 拒收任务被后续成功改派消化。
 */
public enum DiversionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    RESOLVED
}
