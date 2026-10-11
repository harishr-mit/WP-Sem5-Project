package com.mitpass.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated caller, read from the access token's claims. Controllers take it as a method parameter (resolved by
 * {@code CurrentUserArgumentResolver}); other code calls {@link #current()}.
 *
 * @param department department code ({@code dept} claim), may be {@code null}
 * @param year year of study ({@code year} claim), may be {@code null}
 * @param registerNumber {@code regNo} claim, may be {@code null}
 * @param tokenValue the raw access token, for relaying it to another service
 */
public record CurrentUser(
        UUID id,
        String email,
        String name,
        Set<String> roles,
        String department,
        Integer year,
        String registerNumber,
        String tokenValue) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(MitpassClaims.ROLES);
        Object year = jwt.getClaims().get(MitpassClaims.YEAR);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(MitpassClaims.EMAIL),
                jwt.getClaimAsString(MitpassClaims.NAME),
                roles == null ? Set.of() : Set.copyOf(roles),
                jwt.getClaimAsString(MitpassClaims.DEPARTMENT),
                year instanceof Number n ? n.intValue() : null,
                jwt.getClaimAsString(MitpassClaims.REGISTER_NUMBER),
                jwt.getTokenValue());
    }

    /** The caller of the current request, if it carries a valid access token. */
    public static Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(from(token.getToken()));
        }
        return Optional.empty();
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(Collection<String> wanted) {
        return wanted.stream().anyMatch(roles::contains);
    }

    /** Never prints the raw token. */
    @Override
    public String toString() {
        return "CurrentUser[id=%s, email=%s, roles=%s]".formatted(id, email, roles);
    }
}
