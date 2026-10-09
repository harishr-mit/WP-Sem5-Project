package com.mitpass.event.web.dto;

import com.mitpass.event.validation.TimeWindow;
import com.mitpass.event.validation.ValidTimeWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** A session must also lie inside its event's window; that needs the event, so the service checks it (422). */
@ValidTimeWindow
public record SessionRequest(
        @Schema(example = "Day 1: Kick-off and build") @NotBlank @Size(max = 160)
        String title,

        @Schema(example = "2026-11-14T03:30:00Z") @NotNull Instant startsAt,
        @Schema(example = "2026-11-14T12:30:00Z") @NotNull Instant endsAt)
        implements TimeWindow {}
