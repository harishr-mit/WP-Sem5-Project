package com.mitpass.event.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record VenueRequest(
        @Schema(example = "Mini Auditorium") @NotBlank @Size(max = 120)
        String name,

        @Schema(example = "Academic Block 3") @Size(max = 80)
        String building,

        @Schema(example = "300") @NotNull @Positive @Max(100_000)
        Integer capacity,

        @Schema(example = "https://maps.example.org/mini-auditorium") @Size(max = 500) @URL
        String mapUrl) {}
