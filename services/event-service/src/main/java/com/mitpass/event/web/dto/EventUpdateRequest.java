package com.mitpass.event.web.dto;

import com.mitpass.event.validation.TimeWindow;
import com.mitpass.event.validation.ValidTimeWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code PUT /api/events/{id}}: replaces the event's own fields (not its sessions or status). Send the
 * {@code version} you read to get optimistic locking: a stale version is rejected with 409.
 */
@ValidTimeWindow
public record EventUpdateRequest(
        @NotBlank @Pattern(regexp = EventCodes.PATTERN, message = EventCodes.MESSAGE)
        String code,

        @NotBlank @Size(min = 3, max = 160) String title,
        @Size(max = 4000) String description,
        @NotNull Long categoryId,
        @NotNull Long venueId,
        @Size(max = 80) String department,
        @NotNull @Positive @Max(100_000) Integer capacity,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        Instant registrationOpensAt,
        Instant registrationClosesAt,

        @Schema(description = "Version from the last GET; optional, but without it the update can't detect conflicts")
        Long version)
        implements TimeWindow {}
