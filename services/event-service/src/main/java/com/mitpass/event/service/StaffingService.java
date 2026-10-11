package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.event.client.AuthServiceClient;
import com.mitpass.event.client.AuthServiceClient.UserAccount;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventGate;
import com.mitpass.event.domain.VolunteerAssignment;
import com.mitpass.event.repository.EventGateRepository;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.VolunteerAssignmentRepository;
import com.mitpass.event.web.dto.StaffingDtos.AssignVolunteerRequest;
import com.mitpass.event.web.dto.StaffingDtos.AssignmentResponse;
import com.mitpass.event.web.dto.StaffingDtos.GateRequest;
import com.mitpass.event.web.dto.StaffingDtos.GateResponse;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gates of an event and the volunteers assigned to them. Authorization is on the controller. */
@Service
@Transactional(readOnly = true)
public class StaffingService {

    private static final String VOLUNTEER = "VOLUNTEER";

    private final EventRepository events;
    private final EventGateRepository gates;
    private final VolunteerAssignmentRepository assignments;
    private final AuthServiceClient authService;
    private final Clock clock;

    public StaffingService(
            EventRepository events,
            EventGateRepository gates,
            VolunteerAssignmentRepository assignments,
            AuthServiceClient authService,
            Clock clock) {
        this.events = events;
        this.gates = gates;
        this.assignments = assignments;
        this.authService = authService;
        this.clock = clock;
    }

    // ---- gates ------------------------------------------------------------------------------------------------

    public List<GateResponse> gates(long eventId) {
        requireEvent(eventId);
        return gates.findByEventIdOrderByCode(eventId).stream()
                .map(GateResponse::from)
                .toList();
    }

    @Transactional
    public GateResponse addGate(long eventId, GateRequest request) {
        Event event = requireEvent(eventId);
        String code = request.code().toUpperCase(Locale.ROOT);
        if (gates.existsByEventIdAndCode(eventId, code)) {
            throw new ConflictException("Event %d already has a gate %s".formatted(eventId, code));
        }
        return GateResponse.from(
                gates.save(new EventGate(event, code, request.name().trim())));
    }

    /** Volunteers assigned to this gate stay assigned, to any gate of the event (FK {@code ON DELETE SET NULL}). */
    @Transactional
    public void removeGate(long eventId, long gateId) {
        EventGate gate = gates.findByIdAndEventId(gateId, eventId)
                .orElseThrow(() -> NotFoundException.of("Gate", gateId + " of event " + eventId));
        gates.delete(gate);
    }

    // ---- volunteers -------------------------------------------------------------------------------------------

    public List<AssignmentResponse> volunteers(long eventId) {
        requireEvent(eventId);
        return assignments.findByEventIdOrderByVolunteerName(eventId).stream()
                .map(AssignmentResponse::from)
                .toList();
    }

    /**
     * Looks the volunteer up in auth-service (with the organizer's token) and records the assignment. The account must
     * hold the VOLUNTEER role, which an admin grants.
     */
    @Transactional
    public AssignmentResponse assign(long eventId, AssignVolunteerRequest request, CurrentUser organizer) {
        Event event = requireEvent(eventId);
        EventGate gate = request.gateId() == null
                ? null
                : gates.findByIdAndEventId(request.gateId(), eventId)
                        .orElseThrow(() -> new BusinessRuleException(
                                "Gate %d is not a gate of event %d".formatted(request.gateId(), eventId)));

        UserAccount account = authService.findByEmail(request.email());
        if (account.roles() == null || !account.roles().contains(VOLUNTEER)) {
            throw new BusinessRuleException(
                    "%s is not a volunteer. An admin must grant the VOLUNTEER role first.".formatted(account.email()));
        }
        if (assignments.existsByEventIdAndVolunteerId(eventId, account.id())) {
            throw new ConflictException("%s is already assigned to event %d".formatted(account.email(), eventId));
        }
        VolunteerAssignment saved = assignments.saveAndFlush(new VolunteerAssignment(
                event,
                gate,
                account.id(),
                account.fullName(),
                account.email(),
                clock.instant(),
                organizer.id().toString()));
        return AssignmentResponse.from(saved);
    }

    @Transactional
    public void unassign(long eventId, UUID volunteerId) {
        VolunteerAssignment assignment = assignments
                .findByEventIdAndVolunteerId(eventId, volunteerId)
                .orElseThrow(
                        () -> NotFoundException.of("Assignment of volunteer", volunteerId + " to event " + eventId));
        assignments.delete(assignment);
    }

    public List<AssignmentResponse> assignmentsOf(UUID volunteerId) {
        return assignments.findByVolunteerIdOrderByEventStartsAt(volunteerId).stream()
                .map(AssignmentResponse::from)
                .toList();
    }

    private Event requireEvent(long eventId) {
        return events.findById(eventId).orElseThrow(() -> NotFoundException.of("Event", eventId));
    }
}
