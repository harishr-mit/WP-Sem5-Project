package com.mitpass.common.pass;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.pass.PassTokenVerifier.Failure;
import com.mitpass.common.pass.PassTokenVerifier.Result;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PassTokenVerifierTest {

    static final UUID PASS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000005");
    static final Instant IAT = Instant.parse("2026-11-14T03:00:00Z");

    final ECKey key = generate("k1");
    final ECKey otherKey = generate("k1"); // same kid, different key: a forgery
    final Function<String, Optional<ECKey>> jwks =
            kid -> Optional.ofNullable(Map.of("k1", key.toPublicJWK()).get(kid));
    final PassTokenVerifier verifier = new PassTokenVerifier(PassTokens.DEFAULT_ISSUER);

    @Test
    void aGenuineTokenGivesItsClaims() {
        Result result = verifier.verify(sign(key, claims().build(), PassTokens.TYPE), jwks);

        assertThat(result.verified()).isTrue();
        assertThat(result.token().passId()).isEqualTo(PASS);
        assertThat(result.token().eventId()).isEqualTo(42L);
        assertThat(result.token().userId()).isEqualTo(USER);
        assertThat(result.token().kind()).isEqualTo(PassTokens.KIND_LIVE);
        assertThat(result.token().isStatic()).isFalse();
        assertThat(result.token().expiresAt()).isEqualTo(IAT.plusSeconds(45));
        assertThat(result.token().jti()).isEqualTo("j-1");
        assertThat(result.token().kid()).isEqualTo("k1");
    }

    @Test
    void oneChangedCharacterAnywhereBreaksTheSignature() {
        String token = sign(key, claims().build(), PassTokens.TYPE);
        String[] parts = token.split("\\.");
        // Payload: change the event id inside the JSON, keep valid base64url.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"eid\":42", "\"eid\":43");
        String edited = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
                + "." + parts[2];
        assertThat(verifier.verify(edited, jwks).failure()).isEqualTo(Failure.BAD_SIGNATURE);

        char last = parts[2].charAt(5);
        String badSignature = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 5) + (last == 'A' ? 'B' : 'A')
                + parts[2].substring(6);
        assertThat(verifier.verify(badSignature, jwks).failure()).isEqualTo(Failure.BAD_SIGNATURE);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsAForgery() {
        assertThat(verifier.verify(sign(otherKey, claims().build(), PassTokens.TYPE), jwks)
                        .failure())
                .isEqualTo(Failure.BAD_SIGNATURE);
    }

    @Test
    void unknownKidWrongTypeWrongIssuerAndGarbageAreRefused() {
        ECKey stranger = generate("k9");
        assertThat(verifier.verify(sign(stranger, claims().build(), PassTokens.TYPE), jwks)
                        .failure())
                .isEqualTo(Failure.UNKNOWN_KID);
        assertThat(verifier.verify(sign(key, claims().build(), JOSEObjectType.JWT), jwks)
                        .failure())
                .as("a user access token is not a pass")
                .isEqualTo(Failure.WRONG_TYPE);
        assertThat(verifier.verify(sign(key, claims().issuer("mitpass-auth").build(), PassTokens.TYPE), jwks)
                        .failure())
                .isEqualTo(Failure.WRONG_TYPE);
        assertThat(verifier.verify("not-a-token", jwks).failure()).isEqualTo(Failure.MALFORMED);
        assertThat(verifier.verify("", jwks).failure()).isEqualTo(Failure.MALFORMED);
        assertThat(verifier.verify(null, jwks).failure()).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void anHmacTokenIsRefusedBeforeAnyKeyIsUsed() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .keyID("k1")
                        .type(PassTokens.TYPE)
                        .build(),
                claims().build());
        jwt.sign(new MACSigner(new byte[32]));

        assertThat(verifier.verify(jwt.serialize(), jwks).failure()).isEqualTo(Failure.WRONG_TYPE);
    }

    @Test
    void missingClaimsAreMalformed() {
        JWTClaimsSet noPassId = new JWTClaimsSet.Builder(claims().build())
                .claim(PassTokens.PASS_ID, null)
                .build();
        assertThat(verifier.verify(sign(key, noPassId, PassTokens.TYPE), jwks).failure())
                .isEqualTo(Failure.MALFORMED);
    }

    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(PassTokens.DEFAULT_ISSUER)
                .subject(USER.toString())
                .issueTime(Date.from(IAT))
                .expirationTime(Date.from(IAT.plusSeconds(45)))
                .jwtID("j-1")
                .claim(PassTokens.PASS_ID, PASS.toString())
                .claim(PassTokens.EVENT_ID, 42L)
                .claim(PassTokens.KIND, PassTokens.KIND_LIVE);
    }

    static String sign(ECKey key, JWTClaimsSet claims, JOSEObjectType type) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(key.getKeyID())
                            .type(type)
                            .build(),
                    claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
