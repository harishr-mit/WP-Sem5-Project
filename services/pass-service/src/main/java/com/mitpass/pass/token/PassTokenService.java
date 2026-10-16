package com.mitpass.pass.token;

import com.mitpass.common.pass.PassTokens;
import com.mitpass.pass.config.PassProperties;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.keys.PassKeyService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints the compact JWS a QR code carries (docs/DECISIONS.md D12): header {@code {alg: ES256, kid, typ:
 * "mitpass-pass+jws"}}, claims {@code iss, pid, eid, sub, iat, exp, jti, knd}. Short claim names keep the QR small.
 * Tokens aren't stored: revocation goes by pass id. Signed with Nimbus directly, so Spring's {@code JwtEncoder} /
 * {@code JwtDecoder} beans (RS256 user tokens) are never involved.
 */
@Service
public class PassTokenService {

    public static final JOSEObjectType TYPE = PassTokens.TYPE;

    /** Live (rotating, short) or static (PDF/email, until the event ends). */
    public enum Kind {
        LIVE(PassTokens.KIND_LIVE),
        STATIC(PassTokens.KIND_STATIC);

        private final String claim;

        Kind(String claim) {
            this.claim = claim;
        }

        public String claim() {
            return claim;
        }
    }

    public record PassToken(String value, Instant issuedAt, Instant expiresAt, Kind kind, String kid) {}

    private final PassKeyService keys;
    private final PassProperties properties;
    private final Clock clock;

    public PassTokenService(PassKeyService keys, PassProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /** A live token: valid for {@code live-token-ttl} (45 s). */
    public PassToken live(Pass pass) {
        Instant now = clock.instant();
        return sign(pass, Kind.LIVE, now, now.plus(properties.liveTokenTtl()));
    }

    /** A static token valid until {@code expiresAt}; the key keeps being published until then (D12). */
    @Transactional
    public PassToken staticToken(Pass pass, Instant expiresAt) {
        PassToken token = sign(pass, Kind.STATIC, clock.instant(), expiresAt);
        keys.recordTokenExpiry(token.kid(), expiresAt);
        return token;
    }

    PassToken sign(Pass pass, Kind kind, Instant issuedAt, Instant expiresAt) {
        // JWS dates are whole seconds: truncate first, so exp - iat is exact and the reported times match the token.
        issuedAt = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        ECKey key = keys.activeKey();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(pass.getUserId().toString())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(PassTokens.PASS_ID, pass.getId().toString())
                .claim(PassTokens.EVENT_ID, pass.getEvent().getId())
                .claim(PassTokens.KIND, kind.claim())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(key.getKeyID())
                .type(TYPE)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign a pass token", ex);
        }
        return new PassToken(jwt.serialize(), issuedAt, expiresAt, kind, key.getKeyID());
    }
}
