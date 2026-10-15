package com.mitpass.common.messaging.contract;

import java.time.Instant;

/**
 * An event moved through its lifecycle (PROJECT.md §3). pass-service (P4) revokes every pass when {@code toStatus} is
 * CANCELLED; checkin-service (P5) accepts scans only while LIVE. States are enum names as strings so consumers don't
 * share event-service's enum. Contract: fields are only ever added.
 */
@EventType("event.state-changed")
public record EventStateChanged(
        long eventId,
        String eventCode,
        String fromStatus,
        String toStatus,
        Instant changedAt,
        String changedBy,
        String trigger,
        Instant startsAt,
        Instant endsAt) {}
