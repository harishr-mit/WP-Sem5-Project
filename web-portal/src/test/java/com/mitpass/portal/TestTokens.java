package com.mitpass.portal;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Access tokens shaped like auth-service's (D7), and logged-in portal users for MockMvc. */
public final class TestTokens {

    public static final String ACCESS = "SECRET-ACCESS-TOKEN-123";
    public static final String REFRESH = "SECRET-REFRESH-TOKEN-456";

    private static final RSAKey KEY;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("portal-test").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private TestTokens() {}

    public static TokenResponse tokenResponse(UUID id, String name, List<String> roles, String dept, Integer year) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(id.toString())
                    .claim("email", "user@test")
                    .claim("name", name)
                    .claim("roles", roles)
                    .claim("dept", dept)
                    .expirationTime(Date.from(Instant.now().plusSeconds(900)));
            if (year != null) {
                claims.claim("year", year);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return new TokenResponse(jwt.serialize(), 900, REFRESH);
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static PortalUser user(String... roles) {
        return user(Instant.now().plusSeconds(900), roles);
    }

    public static PortalUser user(Instant expiresAt, String... roles) {
        return new PortalUser(
                UUID.fromString("00000000-0000-4000-8000-000000000005"),
                "2026509001@student.annauniv.edu",
                "Priya Raman",
                Set.of(roles),
                "CSE",
                1,
                ACCESS,
                REFRESH,
                expiresAt);
    }

    public static Authentication login(PortalUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                user.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }
}
