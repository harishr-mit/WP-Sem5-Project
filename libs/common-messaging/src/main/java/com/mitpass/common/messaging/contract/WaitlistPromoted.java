package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A waitlisted student got a seat (for the "you're in" email, P7). The same transaction also publishes
 * {@link RegistrationConfirmed} with {@code viaPromotion = true}. {@code freedByRegistrationId} is null when the seat
 * came from a capacity increase. Contract: fields are only ever added.
 */
@EventType("registration.waitlist-promoted")
public record WaitlistPromoted(
        UUID registrationId,
        long eventId,
        String eventCode,
        UUID userId,
        String userEmail,
        Instant promotedAt,
        UUID freedByRegistrationId) {}
