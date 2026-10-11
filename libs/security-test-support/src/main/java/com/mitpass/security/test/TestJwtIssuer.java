package com.mitpass.security.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mints MITPass-shaped access tokens in tests, signed with a fixed in-memory RSA key ({@code kid=test-key}). Pair it
 * with {@link TestSecurityConfig} (servlet) or {@link TestReactiveSecurityConfig} (gateway), which trust exactly this
 * key and run the production claim validators. Also produces the bad tokens every service must reject.
 */
public final class TestJwtIssuer {

    public static final String KID = "test-key";
    public static final String ISSUER = "mitpass-auth";
    public static final String AUDIENCE = "mitpass-api";

    private static final RSAKey KEY = generate(KID);
    private static final RSAKey OTHER_KEY = generate("attacker-key");

    private TestJwtIssuer() {}

    public static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The test key as a public JWK, e.g. to serve as a JWK set. */
    public static RSAKey publicJwk() {
        return KEY.toPublicJWK();
    }

    /** A valid token for a new random user with these roles. */
    public static String token(String... roles) {
        return tokenFor(UUID.randomUUID(), roles);
    }

    /** A valid token for this user id with these roles. */
    public static String tokenFor(UUID userId, String... roles) {
        return sign(claims(userId, roles).build());
    }

    /** Claims shaped like auth-service's: iss, aud, sub, email, name, roles, iat, exp (+15 min), jti. */
    public static JWTClaimsSet.Builder claims(UUID userId, String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(userId.toString())
                .claim("email", "user-" + userId.toString().substring(0, 8) + "@mitpass.test")
                .claim("name", "Test User")
                .claim("roles", List.of(roles))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .jwtID(UUID.randomUUID().toString());
    }

    public static String sign(JWTClaimsSet claims) {
        return sign(claims, KEY);
    }

    public static String expired(String... roles) {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return sign(claims(UUID.randomUUID(), roles)
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(Duration.ofMinutes(15))))
                .build());
    }

    public static String wrongIssuer(String... roles) {
        return sign(claims(UUID.randomUUID(), roles).issuer("someone-else").build());
    }

    public static String wrongAudience(String... roles) {
        return sign(claims(UUID.randomUUID(), roles).audience("another-api").build());
    }

    /** Correct claims, but signed by a key the services don't trust. */
    public static String signedByOtherKey(String... roles) {
        return sign(claims(UUID.randomUUID(), roles).build(), OTHER_KEY);
    }

    /** An unsigned token ({@code "alg":"none"}). */
    public static String algNone(String... roles) {
        return new PlainJWT(claims(UUID.randomUUID(), roles).build()).serialize();
    }

    /**
     * Takes a real token and rewrites its payload to {@code roles=["ADMIN"]}, keeping the original signature: the classic
     * "edit the JWT" attack. Signature verification must fail.
     */
    public static String forged(String token) {
        String[] parts = token.split("\\.");
        try {
            Map<String, Object> payload = JWTClaimsSet.parse(
                            Base64URL.from(parts[1]).decodeToString())
                    .toJSONObject();
            payload.put("roles", List.of("ADMIN"));
            String edited =
                    Base64URL.encode(JWTClaimsSet.parse(payload).toString()).toString();
            return parts[0] + "." + edited + "." + parts[2];
        } catch (ParseException ex) {
            throw new IllegalArgumentException("Not a JWT", ex);
        }
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
