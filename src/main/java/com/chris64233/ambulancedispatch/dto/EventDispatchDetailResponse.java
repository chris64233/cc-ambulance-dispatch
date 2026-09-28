package com.chris64233.ambulancedispatch.dto;

import java.util.List;

/**
 * 事件完整详情：事件信息、当前进行中的派遣、全部派遣历史、医院预留、历次改派、
 * 拒收记录、改派任务与完整时间线。
 */
public record EventDispatchDetailResponse(
        EventResponse event,
        DispatchResponse currentDispatch,
        List<DispatchResponse> dispatches,
        List<ReservationResponse> reservations,
        List<DiversionResponse> diversions,
        List<RejectionResponse> rejections,
        List<DiversionTaskResponse> diversionTasks,
        List<EventTimelineEntryResponse> timeline) {
}
