package com.mitpass.common.messaging.contract;

import java.time.Instant;

/**
 * An event's start or end time changed (owner decision 2026-10-16, D13). pass-service moves its pass unlock and expiry
 * times. Not published for DRAFT events (nothing depends on them yet). Contract: fields are only ever added.
 */
@EventType("event.rescheduled")
public record EventRescheduled(
        long eventId, String eventCode, Instant startsAt, Instant endsAt, Instant changedAt, String changedBy) {}
