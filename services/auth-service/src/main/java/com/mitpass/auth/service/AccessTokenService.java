package com.mitpass.auth.service;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.keys.SigningKeyService;
import com.mitpass.common.security.MitpassClaims;
import com.mitpass.common.security.MitpassSecurityProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Mints RS256 access tokens with the active signing key ({@code kid} in the header). */
@Service
public class AccessTokenService {

    private final SigningKeyService signingKeys;
    private final MitpassSecurityProperties security;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, JwtEncoder> encoders = new ConcurrentHashMap<>();

    public AccessTokenService(
            SigningKeyService signingKeys, MitpassSecurityProperties security, AuthProperties properties, Clock clock) {
        this.signingKeys = signingKeys;
        this.security = security;
        this.ttl = properties.accessTokenTtl();
        this.clock = clock;
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {}

    /** Needs the user's roles and department loaded (call inside the transaction that read the user). */
    public IssuedAccessToken mint(User user) {
        RSAKey key = signingKeys.activeKey();
        JwtEncoder encoder = encoders.computeIfAbsent(
                key.getKeyID(), kid -> new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))));
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(security.getIssuer())
                .audience(List.of(security.getAudience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(MitpassClaims.EMAIL, user.getEmail())
                .claim(MitpassClaims.NAME, user.getFullName())
                .claim(
                        MitpassClaims.ROLES,
                        user.roleNames().stream().map(RoleName::name).toList())
                .claim(MitpassClaims.DEPARTMENT, user.getDepartment().getCode());
        if (user.getYearOfStudy() != null) {
            claims.claim(MitpassClaims.YEAR, user.getYearOfStudy());
        }
        if (user.getRegisterNumber() != null) {
            claims.claim(MitpassClaims.REGISTER_NUMBER, user.getRegisterNumber());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(key.getKeyID())
                .type("JWT")
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }
}
