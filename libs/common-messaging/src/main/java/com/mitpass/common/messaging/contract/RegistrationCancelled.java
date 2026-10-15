package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration was cancelled by the student, the event's organizer or an admin. {@code previousStatus} is CONFIRMED
 * or WAITLISTED; only a CONFIRMED one held a seat (and, from P4, a pass). Contract: fields are only ever added.
 */
@EventType("registration.cancelled")
public record RegistrationCancelled(
        UUID registrationId,
        long eventId,
        String eventCode,
        UUID userId,
        String previousStatus,
        Instant cancelledAt,
        String cancelledBy) {}
