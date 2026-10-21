package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * The event was full, so the registration joined the waitlist at {@code position} (1 = next in line). Added in P7 for
 * the "you're on the waitlist" email (D16). Contract: fields are only ever added.
 */
@EventType("registration.waitlisted")
public record RegistrationWaitlisted(
        UUID registrationId,
        long eventId,
        String eventCode,
        String eventTitle,
        UUID userId,
        String userName,
        String userEmail,
        int position,
        Instant waitlistedAt) {}
