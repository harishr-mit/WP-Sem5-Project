package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventStateChange;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import java.time.Instant;

/** One entry of {@code GET /api/events/{id}/history}; {@code fromStatus} is null for the creation entry. */
public record StateChangeResponse(
        EventStatus fromStatus,
        EventStatus toStatus,
        Instant changedAt,
        String changedBy,
        TransitionTrigger trigger,
        String reason) {

    public static StateChangeResponse from(EventStateChange change) {
        return new StateChangeResponse(
                change.getFromStatus(),
                change.getToStatus(),
                change.getChangedAt(),
                change.getChangedBy(),
                change.getTrigger(),
                change.getReason());
    }
}
