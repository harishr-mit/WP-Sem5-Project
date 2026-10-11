package com.mitpass.event.web.dto;

import com.mitpass.event.domain.EventGate;
import com.mitpass.event.domain.VolunteerAssignment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies for gates and volunteer assignments. */
public final class StaffingDtos {

    private StaffingDtos() {}

    public record GateRequest(
            @Schema(example = "MAIN")
            @NotBlank
            @Pattern(regexp = "^[A-Z0-9-]{2,16}$", message = "must be 2-16 of A-Z, 0-9 and -")
            String code,

            @Schema(example = "Main Gate") @NotBlank @Size(max = 80)
            String name) {}

    public record GateResponse(Long id, String code, String name) {

        public static GateResponse from(EventGate gate) {
            return gate == null ? null : new GateResponse(gate.getId(), gate.getCode(), gate.getName());
        }
    }

    public record AssignVolunteerRequest(
            @Schema(example = "volunteer@mitpass.test") @NotBlank @Email @Size(max = 254)
            String email,

            @Schema(description = "A gate of this event; leave out for any gate")
            Long gateId) {}

    /** {@code gate} is {@code null} when the volunteer may scan at any gate of the event. */
    public record AssignmentResponse(
            Long eventId,
            String eventCode,
            String eventTitle,
            Instant eventStartsAt,
            UUID volunteerId,
            String volunteerName,
            String volunteerEmail,
            GateResponse gate,
            Instant assignedAt,
            String assignedBy) {

        public static AssignmentResponse from(VolunteerAssignment assignment) {
            return new AssignmentResponse(
                    assignment.getEvent().getId(),
                    assignment.getEvent().getCode(),
                    assignment.getEvent().getTitle(),
                    assignment.getEvent().getStartsAt(),
                    assignment.getVolunteerId(),
                    assignment.getVolunteerName(),
                    assignment.getVolunteerEmail(),
                    GateResponse.from(assignment.getGate()),
                    assignment.getAssignedAt(),
                    assignment.getAssignedBy());
        }
    }
}
