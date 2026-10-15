package com.mitpass.event.web;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.service.RegistrationService;
import com.mitpass.event.service.RegistrationService.RegisterOutcome;
import com.mitpass.event.web.dto.RegistrationRequests.Cancel;
import com.mitpass.event.web.dto.RegistrationRequests.Register;
import com.mitpass.event.web.dto.RegistrationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/registrations")
@Tag(name = "Registrations", description = "Register for events, cancel, and see your registrations")
public class RegistrationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final RegistrationService registrations;

    public RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(
            summary = "Register for an event (STUDENT)",
            description = "201 with status CONFIRMED (a seat was free) or WAITLISTED (with `waitlistPosition`). "
                    + "409 `registration-closed` / `already-registered`, 422 `not-eligible` with `reasons`. "
                    + "With an `Idempotency-Key`, a retry returns the first response (`Idempotent-Replayed: true`); "
                    + "the same key with another body gives 422 `idempotency-key-mismatch`.")
    public ResponseEntity<RegistrationResponse> register(
            @Valid @RequestBody Register request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
                    @Pattern(regexp = "[A-Za-z0-9_-]{1,80}", message = "must be 1-80 letters, digits, - or _")
                    String idempotencyKey,
            CurrentUser student) {
        RegisterOutcome outcome = registrations.register(student, request.eventId(), idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/registrations/{id}")
                .buildAndExpand(outcome.response().id())
                .toUri();
        ResponseEntity.BodyBuilder created = ResponseEntity.created(location);
        if (outcome.replayed()) {
            created.header(REPLAYED, "true");
        }
        return created.body(outcome.response());
    }

    @GetMapping("/me")
    @Operation(summary = "My registrations, newest first, with waitlist positions")
    public PageResponse<RegistrationResponse> mine(
            @RequestParam(required = false) RegistrationStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable,
            CurrentUser me) {
        return registrations.mine(me.id(), status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@registrationSecurity.canAccess(#id)")
    @Operation(summary = "One registration (its student, the event's organizer, or ADMIN)")
    public RegistrationResponse get(@PathVariable UUID id) {
        return registrations.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@registrationSecurity.canAccess(#id)")
    @Operation(
            summary = "Cancel a registration (its student, the event's organizer, or ADMIN)",
            description = "A cancelled CONFIRMED seat goes to the first waitlisted student in the same transaction "
                    + "(`promotedRegistrationId`). Allowed until the event goes LIVE.")
    public RegistrationResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody(required = false) Cancel request, CurrentUser caller) {
        return registrations.cancel(id, caller.id().toString(), request == null ? null : request.reason());
    }
}
