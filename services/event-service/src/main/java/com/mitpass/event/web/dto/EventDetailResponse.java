package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventStatus;
import java.time.Instant;
import java.util.List;

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
        Long version,
        List<SessionResponse> sessions,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {}
