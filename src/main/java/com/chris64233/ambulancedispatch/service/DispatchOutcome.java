package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.domain.Dispatch;

/**
 * 派遣结果。
 *
 * @param dispatch 派遣单
 * @param replayed 是否为幂等重放（相同业务号 + 相同内容返回原结果）
 */
public record DispatchOutcome(Dispatch dispatch, boolean replayed) {
}
