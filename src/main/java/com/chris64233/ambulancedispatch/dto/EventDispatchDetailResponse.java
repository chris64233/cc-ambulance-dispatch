package com.chris64233.ambulancedispatch.dto;

import java.util.List;

/**
 * 事件派遣详情：事件信息、当前进行中的派遣（若有）、当前目的医院预留情况、
 * 全部派遣历史、历次改派/拒收记录与事件完整时间线。
 */
public record EventDispatchDetailResponse(
        EventResponse event,
        DispatchResponse currentDispatch,
        HospitalResponse currentHospital,
        List<DispatchResponse> dispatches,
        List<DiversionResponse> diversions,
        List<TimelineEntryResponse> timeline) {
}
