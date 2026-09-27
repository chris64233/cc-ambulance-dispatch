package com.chris64233.ambulancedispatch.dto;

import java.time.Instant;

public record TimelineEntryResponse(
        Long id,
        String resourceType,
        Long resourceId,
        Long dispatchId,
        String eventId,
        String action,
        Instant occurredAt) {
}
