package com.mitpass.portal.auth;

import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Form login against auth-service through the gateway. The returned tokens go into {@link PortalUser}, which Spring
 * Security keeps in the server-side session. The access token's claims are read (not verified) only to show the
 * user's name and role-dependent links: the portal received the token from auth-service itself, and every service
 * verifies it again on each call (D7).
 */
@Component
public class GatewayAuthenticationProvider implements AuthenticationProvider {

    private final GatewayClient gateway;
    private final Clock clock;

    public GatewayAuthenticationProvider(GatewayClient gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());
        TokenResponse tokens;
        try {
            tokens = gateway.login(email, password);
        } catch (ApiProblemException ex) {
            throw translate(ex);
        } catch (ResourceAccessException ex) {
            throw new AuthenticationServiceException("Login is unavailable right now. Please try again shortly.", ex);
        }
        PortalUser user = toUser(tokens, clock.instant());
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    static AuthenticationException translate(ApiProblemException ex) {
        if (ex.is("email-not-verified")) {
            return new DisabledException(
                    "Please verify your email first: open the link we sent you, then log in again.");
        }
        if (ex.is("account-locked") || ex.getStatus() == 423) {
            return new LockedException(ex.getMessage());
        }
        if (ex.getStatus() == 401 || ex.getStatus() == 400) {
            return new BadCredentialsException("Email or password is wrong.");
        }
        return new AuthenticationServiceException("Login failed: " + ex.getMessage());
    }

    static PortalUser toUser(TokenResponse tokens, Instant now) {
        try {
            JWTClaimsSet claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
            List<String> roles = claims.getStringListClaim("roles");
            Object year = claims.getClaim("year");
            Instant expiresAt = claims.getExpirationTime() != null
                    ? claims.getExpirationTime().toInstant()
                    : now.plusSeconds(tokens.expiresIn());
            Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(new HashSet<>(roles));
            return new PortalUser(
                    UUID.fromString(claims.getSubject()),
                    claims.getStringClaim("email"),
                    claims.getStringClaim("name"),
                    roleSet,
                    claims.getStringClaim("dept"),
                    year instanceof Number n ? n.intValue() : null,
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    expiresAt);
        } catch (ParseException | IllegalArgumentException ex) {
            throw new AuthenticationServiceException("auth-service returned an unreadable token", ex);
        }
    }
}
