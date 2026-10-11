package com.mitpass.event.service;

import com.mitpass.event.domain.EventStatus;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/** Query parameters of {@code GET /api/events}; every field is optional. Bound from the query string. */
public record EventSearchCriteria(
        @Parameter(description = "Case-insensitive text in title or description", example = "hack") @Size(max = 100)
        String q,

        @Parameter(description = "Category code", example = "TECHNICAL")
        String category,

        @Parameter(description = "Organising department", example = "CSE")
        String department,

        @Parameter(description = "Starts on or after this day (Asia/Kolkata)", example = "2026-11-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Parameter(description = "Starts on or before this day (Asia/Kolkata)", example = "2026-11-30")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,

        @Parameter(description = "Lifecycle state") EventStatus status,

        @Parameter(description = "Events of this organizer (user id), e.g. for an organizer's own list")
        UUID organizerId) {}
