package com.mitpass.auth.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.domain.SigningKey;
import com.mitpass.auth.domain.SigningKeyStatus;
import com.mitpass.auth.repository.SigningKeyRepository;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@AuthIntegrationTest
class SigningKeyServiceTest {

    @Autowired
    SigningKeyService signingKeys;

    @Autowired
    SigningKeyRepository repository;

    @Autowired
    KeyEncryptor encryptor;

    @Test
    void activeKeyIsCreatedOnceAndStoredEncrypted() {
        RSAKey first = signingKeys.activeKey();
        RSAKey again = signingKeys.activeKey();

        assertThat(again.getKeyID()).isEqualTo(first.getKeyID());
        assertThat(first.isPrivate()).isTrue();
        SigningKey stored = repository.findById(first.getKeyID()).orElseThrow();
        assertThat(stored.getPrivateKeyEnc()).doesNotContain("\"d\"").contains(":");
        assertThat(stored.getPublicJwk()).doesNotContain("\"d\"");
    }

    @Test
    void rotationKeepsTheOldKeyInTheJwkSet() {
        String before = signingKeys.activeKey().getKeyID();

        String after = signingKeys.rotate();

        JWKSet published = signingKeys.publicJwkSet();
        assertThat(after).isNotEqualTo(before);
        assertThat(signingKeys.activeKey().getKeyID()).isEqualTo(after);
        assertThat(published.getKeys()).extracting(JWK::getKeyID).contains(after, before);
        assertThat(published.getKeys().getFirst().getKeyID()).isEqualTo(after);
        assertThat(published.getKeys()).noneMatch(JWK::isPrivate);
    }

    @Test
    void databaseAllowsOnlyOneActiveKey() {
        signingKeys.activeKey();

        assertThatThrownBy(() -> repository.saveAndFlush(
                        new SigningKey("intruder-" + System.nanoTime(), "RS256", "{}", "x:y", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findByStatus(SigningKeyStatus.ACTIVE)).isPresent();
    }

    @Test
    void encryptionRoundTripsAndUsesAFreshSaltEachTime() {
        String a = encryptor.encrypt("secret jwk");
        String b = encryptor.encrypt("secret jwk");

        assertThat(a).isNotEqualTo(b);
        assertThat(encryptor.decrypt(a)).isEqualTo("secret jwk");
        assertThat(encryptor.decrypt(b)).isEqualTo("secret jwk");
    }
}
