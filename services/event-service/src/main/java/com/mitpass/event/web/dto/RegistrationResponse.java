package com.mitpass.event.web.dto;

import com.mitpass.event.domain.RegistrationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A registration as the API shows it.
 *
 * @param waitlistPosition 1-based, only while WAITLISTED
 * @param promotedRegistrationId only in a cancel response: the registration that moved up into the freed seat
 */
public record RegistrationResponse(
        UUID id,
        long eventId,
        String eventCode,
        String eventTitle,
        Instant eventStartsAt,
        RegistrationStatus status,
        Integer waitlistPosition,
        boolean viaPromotion,
        UUID userId,
        String userName,
        String userEmail,
        String department,
        Integer year,
        Instant createdAt,
        Instant confirmedAt,
        Instant waitlistedAt,
        Instant cancelledAt,
        UUID promotedRegistrationId) {}
