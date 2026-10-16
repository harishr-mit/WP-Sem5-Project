package com.mitpass.checkin;

import com.mitpass.checkin.client.PassKeys;
import com.mitpass.common.pass.PassTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Plays pass-service's signer: one ES256 key, published under {@code kid} "test-1", and tokens signed with it. */
public class TestPassKeys implements PassKeys {

    private final ECKey key = generate("test-1");

    @Override
    public Optional<ECKey> key(String kid) {
        return key.getKeyID().equals(kid) ? Optional.of(key.toPublicJWK()) : Optional.empty();
    }

    /** A live token issued at {@code iat}, valid 45 s (D12). */
    public String live(UUID passId, long eventId, UUID userId, Instant iat) {
        return sign(key, passId, eventId, userId, iat, iat.plusSeconds(45), PassTokens.KIND_LIVE);
    }

    public String staticToken(UUID passId, long eventId, UUID userId, Instant iat, Duration validity) {
        return sign(key, passId, eventId, userId, iat, iat.plus(validity), PassTokens.KIND_STATIC);
    }

    /** Signed by a key nobody published under the same kid: a forgery. */
    public String forged(UUID passId, long eventId, UUID userId, Instant iat) {
        return sign(generate("test-1"), passId, eventId, userId, iat, iat.plusSeconds(45), PassTokens.KIND_LIVE);
    }

    static String sign(ECKey key, UUID passId, long eventId, UUID userId, Instant iat, Instant exp, String kind) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(PassTokens.DEFAULT_ISSUER)
                    .subject(userId.toString())
                    .issueTime(Date.from(iat))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim(PassTokens.PASS_ID, passId.toString())
                    .claim(PassTokens.EVENT_ID, eventId)
                    .claim(PassTokens.KIND, kind)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(key.getKeyID())
                            .type(PassTokens.TYPE)
                            .build(),
                    claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
