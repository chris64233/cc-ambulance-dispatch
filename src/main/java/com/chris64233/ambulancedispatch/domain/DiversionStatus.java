package com.chris64233.ambulancedispatch.domain;

/**
 * 改派申请结果。SUCCESS 新医院预留成功、原名额释放；FAILED 新医院无法预留，原目的地与派遣保持不变。
 */
public enum DiversionStatus {
    SUCCESS,
    FAILED
}
