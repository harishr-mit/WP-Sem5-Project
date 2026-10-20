package com.mitpass.event.web;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.service.RegistrationService;
import com.mitpass.event.web.dto.RegistrationRequests.EligibilityResponse;
import com.mitpass.event.web.dto.RegistrationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{id}")
@Tag(name = "Registrations")
public class EventRegistrationsController {

    private final RegistrationService registrations;

    public EventRegistrationsController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/registrations")
    // SERVICE: notification-service reads the recipients of bulk emails at send time (P7, D16)
    @PreAuthorize("hasRole('ADMIN') or hasRole('SERVICE') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#id))")
    @Operation(summary = "The event's registrations, optionally by status (its organizer, ADMIN or a service)")
    public PageResponse<RegistrationResponse> list(
            @PathVariable long id,
            @RequestParam(required = false) RegistrationStatus status,
            @ParameterObject @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return registrations.forEvent(id, status, pageable);
    }

    @GetMapping("/eligibility")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Can I register? (any signed-in user)",
            description = "Eligibility for the caller with the reasons, seats left, waitlist length, and the "
                    + "caller's active registration if there is one.")
    public EligibilityResponse eligibility(@PathVariable long id, CurrentUser me) {
        return registrations.eligibility(id, me);
    }
}
