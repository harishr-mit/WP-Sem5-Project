package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventDetailResponse(
        Long id,
        String code,
        String title,
        String description,
        CategoryResponse category,
        VenueResponse venue,
        String department,
        int capacity,
        Instant startsAt,
        Instant endsAt,
        Instant registrationOpensAt,
        Instant registrationClosesAt,
        EventStatus status,
        UUID organizerId,
        String organizerName,
        Long version,
        List<SessionResponse> sessions,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        List<String> eligibleDepartments,
        Integer minYear,
        Integer maxYear,
        /** The moves an organizer or admin may make now (never LIVE: only the scheduler starts an event). */
        List<EventStatus> allowedTransitions,
        int seatsTaken,
        int seatsLeft,
        /** Phase 4 (D12): pass-service reads these to render passes. */
        boolean staticQrAllowed,
        String passColor,
        /** Phase 5 (D14): checkin-service reads these. {@code certificateMinSessions} null = all sessions. */
        boolean reentryAllowed,
        Integer certificateMinSessions,
        /** Phase 8 (RL1, D17). */
        boolean waitlistEnabled) {}
