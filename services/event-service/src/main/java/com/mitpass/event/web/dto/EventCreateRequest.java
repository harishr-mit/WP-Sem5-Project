package com.mitpass.event.web.dto;

import com.mitpass.event.validation.PassColor;
import com.mitpass.event.validation.TimeWindow;
import com.mitpass.event.validation.ValidTimeWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
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
 * Body of {@code POST /api/events}. Sessions may be created in the same request (one transaction, cascaded); after that
 * they are managed through {@code /api/events/{id}/sessions}. New events start in {@code DRAFT}.
 */
@ValidTimeWindow
public record EventCreateRequest(
        @Schema(example = "HACKNITE26", description = "Short public code, used later in pass numbers")
        @NotBlank
        @Pattern(regexp = EventCodes.PATTERN, message = EventCodes.MESSAGE)
        String code,

        @Schema(example = "HackNite: 12-hour Build Sprint") @NotBlank @Size(min = 3, max = 160)
        String title,

        @Schema(example = "Overnight build sprint for second-year students.") @Size(max = 4000)
        String description,

        @Schema(example = "1", description = "Category id (GET /api/categories)") @NotNull
        Long categoryId,

        @Schema(example = "3", description = "Venue id (GET /api/venues)") @NotNull
        Long venueId,

        @Schema(example = "CSE") @Size(max = 80) String department,

        @Schema(example = "80", description = "Seats; at most the venue's capacity") @NotNull @Positive @Max(100_000)
        Integer capacity,

        @Schema(example = "2026-11-28T12:30:00Z") @NotNull @Future
        Instant startsAt,

        @Schema(example = "2026-11-29T00:30:00Z") @NotNull Instant endsAt,
        @Schema(example = "2026-11-01T03:30:00Z") Instant registrationOpensAt,
        @Schema(example = "2026-11-27T12:30:00Z") Instant registrationClosesAt,
        List<@Valid @NotNull SessionRequest> sessions,

        @Schema(example = "[\"CSE\", \"IT\"]", description = "Departments allowed to register; empty = all")
        List<@NotNull @Pattern(regexp = EventCodes.DEPARTMENT_PATTERN, message = EventCodes.DEPARTMENT_MESSAGE) String>
                eligibleDepartments,

        @Schema(example = "2", description = "Lowest year of study allowed; empty = no limit") @Min(1) @Max(5)
        Integer minYear,

        @Schema(example = "4", description = "Highest year of study allowed; empty = no limit") @Min(1) @Max(5)
        Integer maxYear,

        @Schema(example = "true", description = "Allow a long-lived QR on the PDF ticket; default true (D12)")
        Boolean staticQrAllowed,

        @Schema(
                example = "#1D4E89",
                description = "Pass colour (#RRGGBB, contrast >= 4.5 against white); empty = maroon")
        @PassColor
        String passColor)
        implements TimeWindow {}
