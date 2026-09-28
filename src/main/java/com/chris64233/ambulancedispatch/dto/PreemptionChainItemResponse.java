package com.chris64233.ambulancedispatch.dto;

import java.time.Instant;

/**
 * 抢占链上的一个节点。
 */
public record PreemptionChainItemResponse(
        Long dispatchId,
        String bizNo,
        Long eventId,
        String eventPriority,
        Long ambulanceId,
        Long crewId,
        Long hospitalId,
        String status,
        Long preemptedDispatchId,
        Instant dispatchedAt) {
}
