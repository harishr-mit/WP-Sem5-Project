package com.mitpass.pass.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.SigningKey;
import com.mitpass.pass.repository.SigningKeyRepository;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** ES256 pass keys: stored encrypted, rotated, and published exactly while their tokens can be valid (D12). */
@PassIntegrationTest
class PassKeyServiceTest {

    @Autowired
    PassKeyService keys;

    @Autowired
    SigningKeyRepository repository;

    @Autowired
    TestClock clock;

    @BeforeEach
    void freshClock() {
        clock.set(TestClock.START);
    }

    @Test
    void activeKeyIsEs256AndStoredEncrypted() {
        ECKey key = keys.activeKey();

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getCurve().getName()).isEqualTo("P-256");
        SigningKey stored = repository.findById(key.getKeyID()).orElseThrow();
        assertThat(stored.getPrivateKeyEnc()).doesNotContain("\"d\"").contains(":");
        assertThat(stored.getPublicJwk()).doesNotContain("\"d\"");
        assertThat(keys.publicJwkSet().getKeys()).noneMatch(JWK::isPrivate);
    }

    @Test
    void retiredKeyStaysPublishedForLiveTokensThenDisappears() {
        // A fresh key first: the context's active key may have signed static tokens in other test classes, and then
        // it rightly stays published for days (flaky on CI, depending on class order; 2026-10-21).
        keys.rotate();
        String before = keys.activeKey().getKeyID();

        String after = keys.rotate();

        assertThat(after).isNotEqualTo(before);
        assertThat(kids()).contains(after, before);
        assertThat(keys.publicJwkSet().getKeys().getFirst().getKeyID()).isEqualTo(after);

        clock.advance(Duration.ofSeconds(45).plus(PassKeyService.GRACE).plusSeconds(1));
        assertThat(kids()).contains(after).doesNotContain(before);
    }

    @Test
    void retiredKeyThatSignedAStaticTokenStaysUntilThatTokenExpires() {
        String signer = keys.activeKey().getKeyID();
        Instant staticExp = TestClock.START.plus(Duration.ofDays(20));
        keys.recordTokenExpiry(signer, staticExp);
        keys.recordTokenExpiry(signer, staticExp.minus(Duration.ofDays(5))); // never lowers it

        keys.rotate();

        clock.set(staticExp);
        assertThat(kids()).contains(signer);
        clock.set(staticExp.plus(PassKeyService.GRACE).plusSeconds(1));
        assertThat(kids()).doesNotContain(signer);
    }

    @Test
    void databaseAllowsOnlyOneActiveKey() {
        keys.activeKey();

        assertThatThrownBy(
                        () -> repository.saveAndFlush(new SigningKey("pass-dup", "ES256", "{}", "x:y", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private java.util.List<String> kids() {
        return keys.publicJwkSet().getKeys().stream().map(JWK::getKeyID).toList();
    }
}
