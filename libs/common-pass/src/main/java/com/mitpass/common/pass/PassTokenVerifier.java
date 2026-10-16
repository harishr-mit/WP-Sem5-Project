package com.mitpass.common.pass;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Checks a pass token's form and ES256 signature (D12). Expiry is left to the caller, because the clock-skew tolerance
 * is a scanner policy. Never throws for bad input: every problem is a {@link Failure}.
 */
public final class PassTokenVerifier {

    /** Why a token was refused. */
    public enum Failure {
        /** Not a compact JWS, or the claims are missing or of the wrong type. */
        MALFORMED,
        /** Not ES256, wrong {@code typ}, or a different issuer. */
        WRONG_TYPE,
        /** No published key has this {@code kid}. */
        UNKNOWN_KID,
        /** The signature doesn't match: edited or forged. */
        BAD_SIGNATURE
    }

    /** The claims of a token whose signature is genuine. */
    public record VerifiedPassToken(
            UUID passId,
            long eventId,
            UUID userId,
            String kind,
            Instant issuedAt,
            Instant expiresAt,
            String jti,
            String kid) {

        public boolean isStatic() {
            return PassTokens.KIND_STATIC.equals(kind);
        }
    }

    /** Either {@code token} or {@code failure} is set. */
    public record Result(VerifiedPassToken token, Failure failure) {

        static Result ok(VerifiedPassToken token) {
            return new Result(token, null);
        }

        static Result fail(Failure failure) {
            return new Result(null, failure);
        }

        public boolean verified() {
            return token != null;
        }
    }

    private final String issuer;

    public PassTokenVerifier(String issuer) {
        this.issuer = Objects.requireNonNull(issuer);
    }

    /**
     * @param keyByKid finds the published public key for a {@code kid} (a JWKS cache in production)
     */
    public Result verify(String compact, Function<String, Optional<ECKey>> keyByKid) {
        if (compact == null || compact.isBlank()) {
            return Result.fail(Failure.MALFORMED);
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compact.strip());
        } catch (ParseException ex) {
            return Result.fail(Failure.MALFORMED);
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                || !PassTokens.TYPE.equals(jwt.getHeader().getType())) {
            return Result.fail(Failure.WRONG_TYPE);
        }
        String kid = jwt.getHeader().getKeyID();
        Optional<ECKey> key = kid == null ? Optional.empty() : keyByKid.apply(kid);
        if (key.isEmpty()) {
            return Result.fail(Failure.UNKNOWN_KID);
        }
        try {
            if (!jwt.verify(new ECDSAVerifier(key.get().toECPublicKey()))) {
                return Result.fail(Failure.BAD_SIGNATURE);
            }
        } catch (JOSEException ex) {
            return Result.fail(Failure.BAD_SIGNATURE);
        }
        // Only now are the claims trusted.
        try {
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer())) {
                return Result.fail(Failure.WRONG_TYPE);
            }
            Object eid = claims.getClaim(PassTokens.EVENT_ID);
            String pid = claims.getStringClaim(PassTokens.PASS_ID);
            Date iat = claims.getIssueTime();
            Date exp = claims.getExpirationTime();
            if (!(eid instanceof Number eventId)
                    || pid == null
                    || claims.getSubject() == null
                    || iat == null
                    || exp == null) {
                return Result.fail(Failure.MALFORMED);
            }
            return Result.ok(new VerifiedPassToken(
                    UUID.fromString(pid),
                    eventId.longValue(),
                    UUID.fromString(claims.getSubject()),
                    claims.getStringClaim(PassTokens.KIND),
                    iat.toInstant(),
                    exp.toInstant(),
                    claims.getJWTID(),
                    kid));
        } catch (ParseException | IllegalArgumentException ex) {
            return Result.fail(Failure.MALFORMED);
        }
    }
}
