package com.chris64233.ambulancedispatch.dto;

import java.util.List;

/**
 * 事件派遣详情：事件信息、当前进行中的派遣（若有）与全部派遣历史。
 */
public record EventDispatchDetailResponse(
        EventResponse event,
        DispatchResponse currentDispatch,
        List<DispatchResponse> dispatches) {
}
