package com.mitpass.event.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and small response bodies of the registration endpoints. */
public final class RegistrationRequests {

    private RegistrationRequests() {}

    /** Body of {@code POST /api/registrations}. */
    public record Register(@Schema(example = "2") @NotNull Long eventId) {}

    /** Body of {@code POST /api/registrations/{id}/cancel}; may be omitted. */
    public record Cancel(
            @Schema(example = "Can't make it") @Size(max = 200)
            String reason) {}

    /**
     * {@code GET /api/events/{id}/eligibility} for the caller.
     *
     * @param myRegistration the caller's active registration for the event, if any
     */
    public record EligibilityResponse(
            boolean eligible,
            boolean registrationOpen,
            List<String> reasons,
            int seatsLeft,
            long waitlistCount,
            RegistrationResponse myRegistration,
            /** Phase 8 (RL1): registration has closed, but students can still join the waitlist until LIVE. */
            boolean waitlistOnly) {}
}
