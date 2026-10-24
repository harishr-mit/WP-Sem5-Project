package com.mitpass.pass.web;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.service.PassLifecycleService;
import com.mitpass.pass.service.PassQueryService;
import com.mitpass.pass.web.dto.PassDtos.PassChangeResponse;
import com.mitpass.pass.web.dto.PassDtos.RevokeRequest;
import com.mitpass.pass.web.dto.PassResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/passes")
@Tag(name = "Passes", description = "Wallet, organizer pass lists and revocation")
public class PassController {

    private final PassQueryService queries;
    private final PassLifecycleService lifecycle;

    public PassController(PassQueryService queries, PassLifecycleService lifecycle) {
        this.queries = queries;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/me")
    @Operation(summary = "My passes (the wallet), newest first")
    public PageResponse<PassResponse> mine(
            @RequestParam(required = false) PassStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "issuedAt", direction = Sort.Direction.DESC)
                    Pageable pageable,
            CurrentUser me) {
        return queries.mine(me.id(), status, pageable);
    }

    @GetMapping
    // SERVICE: checkin-service's reconciler keeps its pass copy in step (docs/plans/phase-5.md §4a, D14).
    @PreAuthorize("hasRole('SERVICE') or @passSecurity.ownsEvent(#eventId)")
    @Operation(summary = "Passes of an event (its organizer, ADMIN, or a service token)")
    public PageResponse<PassResponse> forEvent(
            @RequestParam Long eventId,
            @RequestParam(required = false) PassStatus status,
            @ParameterObject @PageableDefault(size = 50, sort = "issuedAt") Pageable pageable) {
        return queries.forEvent(eventId, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SERVICE') or @passSecurity.canView(#id)")
    @Operation(summary = "One pass (its holder, the event's organizer, ADMIN, or a service token)")
    public PassResponse get(@PathVariable UUID id) {
        return queries.get(id);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("@passSecurity.canView(#id)")
    @Operation(summary = "The pass's state changes, oldest first")
    public List<PassChangeResponse> history(@PathVariable UUID id) {
        return queries.history(id);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("@passSecurity.canManage(#id)")
    @Operation(
            summary = "Revoke a pass (the event's organizer, or ADMIN)",
            description = "ISSUED or ACTIVE → REVOKED; publishes PassRevoked and adds an entry to /api/revocations. "
                    + "409 `illegal-transition` if the pass is already EXPIRED or REVOKED.")
    public PassResponse revoke(
            @PathVariable UUID id, @Valid @RequestBody(required = false) RevokeRequest request, CurrentUser actor) {
        String note = request == null ? null : request.note();
        return queries.toResponse(lifecycle.revokeByStaff(id, actor.id().toString(), actor.hasRole(Roles.ADMIN), note));
    }

    @PostMapping("/{id}/reissue")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Issue a new pass for a revoked pass's registration (ADMIN, RL10)",
            description =
                    "The revoked pass stays revoked (D12); the new one has a new number and publishes PassIssued. "
                            + "409 if the pass isn't REVOKED or the registration already has a valid pass; 422 if the "
                            + "registration was cancelled or the event is cancelled or over.")
    public PassResponse reissue(@PathVariable UUID id, CurrentUser actor) {
        return queries.toResponse(lifecycle.reissueByAdmin(id, actor.id().toString()));
    }
}
