package com.mitpass.event.web;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.service.StaffingService;
import com.mitpass.event.web.dto.StaffingDtos.AssignVolunteerRequest;
import com.mitpass.event.web.dto.StaffingDtos.AssignmentResponse;
import com.mitpass.event.web.dto.StaffingDtos.GateRequest;
import com.mitpass.event.web.dto.StaffingDtos.GateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Gates and volunteer staffing of an event (docs/plans/phase-2.md §6). */
@RestController
@Tag(name = "Staffing", description = "Gates of an event and the volunteers who scan at them")
public class StaffingController {

    private static final String OWNER_OR_ADMIN =
            "hasRole('ADMIN') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#eventId))";

    private final StaffingService staffing;

    public StaffingController(StaffingService staffing) {
        this.staffing = staffing;
    }

    @GetMapping("/api/events/{eventId}/gates")
    @Operation(summary = "List an event's gates (any logged-in user)")
    public List<GateResponse> gates(@PathVariable long eventId) {
        return staffing.gates(eventId);
    }

    @PostMapping("/api/events/{eventId}/gates")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(summary = "Add a gate (event organizer or ADMIN)")
    public ResponseEntity<GateResponse> addGate(@PathVariable long eventId, @Valid @RequestBody GateRequest request) {
        GateResponse gate = staffing.addGate(eventId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(gate.id())
                .toUri();
        return ResponseEntity.created(location).body(gate);
    }

    @DeleteMapping("/api/events/{eventId}/gates/{gateId}")
    @PreAuthorize(OWNER_OR_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a gate (event organizer or ADMIN); its volunteers stay, for any gate")
    public void removeGate(@PathVariable long eventId, @PathVariable long gateId) {
        staffing.removeGate(eventId, gateId);
    }

    @GetMapping("/api/events/{eventId}/volunteers")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(summary = "Volunteers assigned to an event (event organizer or ADMIN)")
    public List<AssignmentResponse> volunteers(@PathVariable long eventId) {
        return staffing.volunteers(eventId);
    }

    @PostMapping("/api/events/{eventId}/volunteers")
    @PreAuthorize(OWNER_OR_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Assign a volunteer by email (event organizer or ADMIN)",
            description = "The account must have the VOLUNTEER role (422 otherwise). Leave out `gateId` for any gate.")
    public AssignmentResponse assign(
            @PathVariable long eventId, @Valid @RequestBody AssignVolunteerRequest request, CurrentUser organizer) {
        return staffing.assign(eventId, request, organizer);
    }

    @DeleteMapping("/api/events/{eventId}/volunteers/{volunteerId}")
    @PreAuthorize(OWNER_OR_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a volunteer from an event (event organizer or ADMIN)")
    public void unassign(@PathVariable long eventId, @PathVariable UUID volunteerId) {
        staffing.unassign(eventId, volunteerId);
    }

    @GetMapping("/api/volunteers/me/assignments")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @Operation(summary = "My assignments (VOLUNTEER): which events and gates I scan at")
    public List<AssignmentResponse> myAssignments(CurrentUser me) {
        return staffing.assignmentsOf(me.id());
    }
}
