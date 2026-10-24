package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event's organizer or an admin voided wrong check-ins of a pass (RL12, D17): they no longer count for reports,
 * certificates or the dashboard, and the pass may be checked in again. {@code sessionIds}: the voided sessions (0 = the
 * whole event). {@code stillCheckedIn}: the pass still has a check-in for another session, so pass-service keeps its
 * {@code checkedInAt}; otherwise it clears {@code checkedInAt} / {@code usedAt}. Contract: fields are only ever added.
 */
@EventType("attendance.voided")
public record AttendanceVoided(
        UUID passId,
        long eventId,
        List<Long> sessionIds,
        boolean stillCheckedIn,
        Instant voidedAt,
        String voidedBy,
        String reason) {}
