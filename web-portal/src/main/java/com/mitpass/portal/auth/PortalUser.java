package com.mitpass.portal.auth;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The logged-in user, kept in the server-side HTTP session (BFF, docs/DECISIONS.md D9). The tokens live here and only
 * here: pages get the display fields, the gateway client gets {@link #accessToken()}, the browser gets neither.
 *
 * @param expiresAt when the access token expires; after that the user logs in again (P3 slice; P6 refreshes)
 */
public record PortalUser(
        UUID id,
        String email,
        String name,
        Set<String> roles,
        String department,
        Integer year,
        String accessToken,
        String refreshToken,
        Instant expiresAt)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** What the header shows. */
    public String displayName() {
        return name != null && !name.isBlank() ? name : email;
    }

    /** Never prints the tokens (they would end up in logs or, through {@code sec:authentication}, in a page). */
    @Override
    public String toString() {
        return "PortalUser[id=%s, email=%s, roles=%s]".formatted(id, email, roles);
    }
}
