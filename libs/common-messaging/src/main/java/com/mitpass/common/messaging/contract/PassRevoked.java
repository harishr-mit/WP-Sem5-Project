package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A pass can no longer be used: revoked by an organizer or admin, or because its registration or event was cancelled
 * ({@code reason}: ORGANIZER, ADMIN, REGISTRATION_CANCELLED, EVENT_CANCELLED). checkin-service (P5) rejects it at the
 * gate. Contract: fields are only ever added.
 */
@EventType("pass.revoked")
public record PassRevoked(
        UUID passId,
        String passNumber,
        UUID registrationId,
        long eventId,
        UUID userId,
        String reason,
        Instant revokedAt,
        String revokedBy) {}
