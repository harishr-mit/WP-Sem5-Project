package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventStatus;
import java.time.Instant;
import java.util.UUID;

/** One row of a search result: no description, no sessions (they are not fetched for lists). */
public record EventSummaryResponse(
        Long id,
        String code,
        String title,
        String categoryCode,
        String categoryName,
        Long venueId,
        String venueName,
        String department,
        int capacity,
        Instant startsAt,
        Instant endsAt,
        EventStatus status,
        UUID organizerId,
        String organizerName,
        int seatsTaken,
        int seatsLeft) {}
