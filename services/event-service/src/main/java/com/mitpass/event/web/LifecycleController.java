package com.mitpass.event.web;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.StateChangeResponse;
import com.mitpass.event.web.dto.TransitionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{id}")
@Tag(name = "Event lifecycle", description = "Move an event through its states and read its history")
public class LifecycleController {

    private final EventLifecycleService lifecycle;

    public LifecycleController(EventLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/transitions")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#id))")
    @Operation(
            summary = "Move an event to another state (its organizer, or ADMIN)",
            description = "Allowed targets are listed in the event's `allowedTransitions`. LIVE is set only by the "
                    + "scheduler; an organizer can undo an early start (LIVE → REGISTRATION_CLOSED). An ADMIN can "
                    + "also un-cancel, start an event now and reopen a completed one (RL10). Anything else "
                    + "gives 409 `illegal-transition` with `allowedStates`.")
    public EventDetailResponse transition(
            @PathVariable long id, @Valid @RequestBody TransitionRequest request, CurrentUser caller) {
        // An admin's move beyond the organizer's buttons is an override, recorded as such (RL10).
        TransitionTrigger trigger =
                caller.hasRole(Roles.ADMIN) ? lifecycle.triggerForAdmin(id, request.to()) : TransitionTrigger.MANUAL;
        return lifecycle.transition(id, request.to(), caller.id().toString(), trigger, request.reason());
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#id))")
    @Operation(summary = "Every state change of the event, oldest first (its organizer, or ADMIN)")
    public List<StateChangeResponse> history(@PathVariable long id) {
        return lifecycle.history(id);
    }
}
