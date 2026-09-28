package com.chris64233.ambulancedispatch.dto;

import java.time.Instant;

/**
 * 事件完整时间线中的一个条目，聚合派遣生命周期、医院预留、拒收与历次改派。
 *
 * @param type 条目类型：DISPATCHED / ARRIVED_SCENE / ARRIVED_HOSPITAL / COMPLETED / CANCELLED /
 *             PREEMPTED / RESERVED / RESERVATION_RELEASED / RESERVATION_REJECTED /
 *             REJECTED / DIVERTED / DIVERSION_FAILED
 * @param dispatchId 关联派遣（若有）
 * @param hospitalId 关联医院（若有）
 * @param detail 人类可读描述，如拒收原因、改派失败原因
 */
public record EventTimelineEntryResponse(
        Instant at,
        String type,
        Long dispatchId,
        Long hospitalId,
        String detail) {
}
