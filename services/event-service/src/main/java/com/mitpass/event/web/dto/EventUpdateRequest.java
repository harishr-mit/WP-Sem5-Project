package com.mitpass.event.web.dto;

import com.mitpass.event.validation.PassColor;
import com.mitpass.event.validation.TimeWindow;
import com.mitpass.event.validation.ValidTimeWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

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
        Long version,

        @Schema(description = "Departments allowed to register; empty = all")
        List<@NotNull @Pattern(regexp = EventCodes.DEPARTMENT_PATTERN, message = EventCodes.DEPARTMENT_MESSAGE) String>
                eligibleDepartments,

        @Min(1) @Max(5) Integer minYear,
        @Min(1) @Max(5) Integer maxYear,

        @Schema(example = "true", description = "Allow a long-lived QR on the PDF ticket; empty = unchanged (D12)")
        Boolean staticQrAllowed,

        @Schema(
                example = "#1D4E89",
                description = "Pass colour (#RRGGBB, contrast >= 4.5 against white); empty = maroon")
        @PassColor
        String passColor,

        @Schema(example = "false", description = "Allow re-entry after checking out (P5); empty = unchanged")
        Boolean reentryAllowed,

        @Schema(example = "2", description = "Sessions needed for a certificate; empty = all sessions") @Min(1) @Max(50)
        Integer certificateMinSessions)
        implements TimeWindow {}
