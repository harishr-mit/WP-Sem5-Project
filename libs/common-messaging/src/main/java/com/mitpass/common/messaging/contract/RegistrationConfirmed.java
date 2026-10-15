package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A student holds a seat: registered straight into a free seat, or promoted from the waitlist ({@code viaPromotion}).
 * pass-service (P4) issues a pass for each one. Contract: fields are only ever added (AGENTS.md).
 */
@EventType("registration.confirmed")
public record RegistrationConfirmed(
        UUID registrationId,
        long eventId,
        String eventCode,
        String eventTitle,
        UUID userId,
        String userName,
        String userEmail,
        Instant confirmedAt,
        boolean viaPromotion,
        Instant eventStartsAt,
        Instant eventEndsAt) {}
