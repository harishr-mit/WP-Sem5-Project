package com.mitpass.auth.web;

import com.mitpass.auth.domain.RevokeReason;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.service.UserAdminService;
import com.mitpass.auth.web.dto.AuthRequests.RoleUpdateRequest;
import com.mitpass.auth.web.dto.AuthResponses.UserResponse;
import com.mitpass.auth.web.dto.AuthResponses.UserSummary;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Your account, and user administration")
public class UserController {

    private final UserAdminService users;
    private final Clock clock;

    public UserController(UserAdminService users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @GetMapping("/me")
    @Operation(summary = "The logged-in user")
    public UserResponse me(CurrentUser me) {
        return UserResponse.from(users.get(me.id()), clock.instant());
    }

    @DeleteMapping("/me/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Log out everywhere (revokes all your refresh tokens)")
    public void logoutEverywhere(CurrentUser me) {
        users.revokeSessions(me.id(), RevokeReason.LOGOUT);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List and search users (ADMIN)", description = "`q` matches email, name or register number.")
    public PageResponse<UserResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RoleName role,
            @ParameterObject @PageableDefault(size = 20, sort = "email") Pageable pageable) {
        var now = clock.instant();
        return PageResponse.from(users.search(q, role, pageable).map(user -> UserResponse.from(user, now)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    @Operation(summary = "One user (ADMIN, or the user themselves)")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(users.get(id), clock.instant());
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(
            summary = "Find a user by email (ORGANIZER, ADMIN)",
            description = "Used by event-service to assign volunteers. Gmail dots and +tags are ignored.")
    public UserSummary lookup(@RequestParam @NotBlank @Email String email) {
        return UserSummary.from(users.lookupByEmail(email));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Replace a user's roles (ADMIN)",
            description = "Ends the user's sessions, so their next login carries the new roles.")
    public UserResponse setRoles(
            @PathVariable UUID id, @Valid @RequestBody RoleUpdateRequest request, CurrentUser admin) {
        return UserResponse.from(users.setRoles(id, request.roles(), admin.id()), clock.instant());
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Lift a login lockout (ADMIN)")
    public void unlock(@PathVariable UUID id) {
        users.unlock(id);
    }

    @DeleteMapping("/{id}/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End all of a user's sessions (ADMIN)")
    public void revokeSessions(@PathVariable UUID id) {
        users.revokeSessions(id, RevokeReason.ADMIN);
    }
}
