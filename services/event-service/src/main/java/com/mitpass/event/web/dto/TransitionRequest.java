package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/events/{id}/transitions}. */
public record TransitionRequest(
        @Schema(example = "PUBLISHED", description = "Target state; see allowedTransitions on the event") @NotNull
        EventStatus to,

        @Schema(example = "Programme confirmed", description = "Optional note for the history") @Size(max = 200)
        String reason) {}
