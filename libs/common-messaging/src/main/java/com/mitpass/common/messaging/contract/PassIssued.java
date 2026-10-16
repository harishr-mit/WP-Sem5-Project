package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * pass-service issued a pass for a confirmed registration. notification-service (P7) sends the ticket email. Contract:
 * fields are only ever added.
 */
@EventType("pass.issued")
public record PassIssued(
        UUID passId,
        String passNumber,
        UUID registrationId,
        long eventId,
        String eventCode,
        String eventTitle,
        UUID userId,
        String holderName,
        String holderEmail,
        Instant issuedAt,
        Instant eventStartsAt) {}
