package com.mitpass.pass.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.keys.PassKeyService;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.pass.repository.SigningKeyRepository;
import com.mitpass.pass.service.PassLifecycleService;
import com.mitpass.pass.token.PassTokenService.PassToken;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Sign/verify round trip; one changed character anywhere fails verification (exit criterion 3, D12 claims). */
@PassIntegrationTest
class PassTokenServiceTest {

    @Autowired
    PassTokenService tokens;

    @Autowired
    PassKeyService keys;

    @Autowired
    PassLifecycleService lifecycle;

    @Autowired
    PassRepository passes;

    @Autowired
    SigningKeyRepository signingKeys;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    Pass pass;

    @BeforeEach
    void issuePass() {
        clock.set(TestClock.START);
        TestEvent event = PassFixtures.event(Duration.ofDays(10));
        UUID registration = UUID.randomUUID();
        tx.executeWithoutResult(status ->
                lifecycle.issue(PassFixtures.confirmed(event, registration, UUID.randomUUID()), TestClock.START));
        pass = tx.execute(status -> {
            Pass loaded = passes.findByRegistrationId(registration).orElseThrow();
            org.hibernate.Hibernate.initialize(loaded.getEvent()); // used outside the transaction
            return loaded;
        });
    }

    @Test
    void liveTokenCarriesTheD12ClaimsAndVerifies() throws Exception {
        PassToken token = tokens.live(pass);

        SignedJWT jwt = SignedJWT.parse(token.value());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("mitpass-pass+jws");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keys.activeKey().getKeyID());
        assertThat(jwt.verify(new ECDSAVerifier(publicKey(jwt.getHeader().getKeyID()))))
                .isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("mitpass-pass");
        assertThat(claims.getSubject()).isEqualTo(pass.getUserId().toString());
        assertThat(claims.getStringClaim("pid")).isEqualTo(pass.getId().toString());
        assertThat(claims.getLongClaim("eid")).isEqualTo(pass.getEvent().getId());
        assertThat(claims.getStringClaim("knd")).isEqualTo("L");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(Duration.between(
                        claims.getIssueTime().toInstant(),
                        claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(45));
        assertThat(token.expiresAt()).isEqualTo(claims.getExpirationTime().toInstant());
    }

    @Test
    void twoLiveTokensDiffer() {
        PassToken first = tokens.live(pass);
        clock.advance(Duration.ofSeconds(30));
        PassToken second = tokens.live(pass);

        assertThat(second.value()).isNotEqualTo(first.value());
        assertThat(second.issuedAt()).isEqualTo(first.issuedAt().plusSeconds(30));
    }

    @Test
    void oneChangedCharacterAnywhereFailsVerification() throws Exception {
        String token = tokens.live(pass).value();
        ECDSAVerifier verifier = new ECDSAVerifier(publicKey(keys.activeKey().getKeyID()));
        String[] parts = token.split("\\.");

        // Edited payload: same JSON with a different pass id, re-encoded (what a forger would do).
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload =
                payload.replace(pass.getId().toString(), UUID.randomUUID().toString());
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertThat(verifies(forged, verifier)).isFalse();

        // One character flipped in each part.
        for (int part = 0; part < 3; part++) {
            int dot = part == 0 ? 0 : token.indexOf('.', part == 1 ? 0 : token.indexOf('.') + 1) + 1;
            int at = dot + 5;
            char flipped = token.charAt(at) == 'A' ? 'B' : 'A';
            String tampered = token.substring(0, at) + flipped + token.substring(at + 1);
            assertThat(verifies(tampered, verifier)).as("part %d", part).isFalse();
        }
        assertThat(verifies(token, verifier)).isTrue();
    }

    @Test
    void aKeyThatIsNotOursDoesNotVerify() throws Exception {
        ECKey stranger = new ECKeyGenerator(Curve.P_256).generate();

        assertThat(verifies(tokens.live(pass).value(), new ECDSAVerifier(stranger.toECPublicKey())))
                .isFalse();
    }

    @Test
    void staticTokenRaisesTheKeysMaxTokenExp() throws Exception {
        Instant exp = pass.getEvent().getEndsAt().plus(Duration.ofHours(2));

        PassToken token = tokens.staticToken(pass, exp);

        SignedJWT jwt = SignedJWT.parse(token.value());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("knd")).isEqualTo("S");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(exp);
        assertThat(signingKeys.findById(token.kid()).orElseThrow().getMaxTokenExp())
                .isAfterOrEqualTo(exp);
    }

    private ECKey publicKey(String kid) {
        return (ECKey) keys.publicJwkSet().getKeyByKeyId(kid);
    }

    private static boolean verifies(String token, ECDSAVerifier verifier) {
        try {
            return JWSObject.parse(token).verify(verifier);
        } catch (Exception ex) {
            return false; // unparseable after tampering counts as failed too
        }
    }
}
