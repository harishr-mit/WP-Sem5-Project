package com.mitpass.auth.keys;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.SigningKey;
import com.mitpass.auth.domain.SigningKeyStatus;
import com.mitpass.auth.repository.SigningKeyRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the RS256 signing keys: creates the first one on demand, rotates on request, and publishes the public halves as
 * the JWK set. A retired key stays in the set for one access-token lifetime plus 5 minutes, so tokens it signed keep
 * verifying after a rotation.
 */
@Service
public class SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);
    private static final Duration RETIRED_KEY_GRACE = Duration.ofMinutes(5);
    private static final DateTimeFormatter KID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SigningKeyRepository keys;
    private final KeyEncryptor encryptor;
    private final Clock clock;
    private final Duration publishRetiredFor;
    private final TransactionTemplate newTransaction;
    private final Map<String, RSAKey> decrypted = new ConcurrentHashMap<>();

    public SigningKeyService(
            SigningKeyRepository keys,
            KeyEncryptor encryptor,
            Clock clock,
            AuthProperties properties,
            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.encryptor = encryptor;
        this.clock = clock;
        this.publishRetiredFor = properties.accessTokenTtl().plus(RETIRED_KEY_GRACE);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The private key that signs new access tokens. Created (and stored encrypted) on first use. */
    public RSAKey activeKey() {
        SigningKey key = keys.findByStatus(SigningKeyStatus.ACTIVE).orElseGet(this::createFirstKey);
        return decrypted.computeIfAbsent(key.getKid(), kid -> parse(encryptor.decrypt(key.getPrivateKeyEnc())));
    }

    /** Public keys that verify currently valid tokens: the active one first, then recently retired ones. */
    public JWKSet publicJwkSet() {
        activeKey();
        Instant retiredAfter = clock.instant().minus(publishRetiredFor);
        List<JWK> published =
                keys.findByStatusOrRetiredAtAfterOrderByCreatedAtDesc(SigningKeyStatus.ACTIVE, retiredAfter).stream()
                        .map(key -> (JWK) parse(key.getPublicJwk()))
                        .toList();
        return new JWKSet(published);
    }

    /** Retires the active key and makes a new one. Tokens signed by the old key stay valid until they expire. */
    @Transactional
    public String rotate() {
        Instant now = clock.instant();
        keys.findByStatus(SigningKeyStatus.ACTIVE).ifPresent(current -> {
            current.retire(now);
            keys.saveAndFlush(current);
        });
        SigningKey next = keys.save(newKey(now));
        log.info("Signing key rotated: new kid {}", next.getKid());
        return next.getKid();
    }

    private SigningKey createFirstKey() {
        try {
            SigningKey created = newTransaction.execute(status -> keys.saveAndFlush(newKey(clock.instant())));
            log.info("Created signing key {}", created.getKid());
            return created;
        } catch (DataIntegrityViolationException race) {
            // Another instance created one at the same moment: the partial unique index kept only theirs.
            return keys.findByStatus(SigningKeyStatus.ACTIVE).orElseThrow(() -> race);
        }
    }

    private SigningKey newKey(Instant now) {
        String kid = "mitpass-" + KID_TIME.format(now) + "-"
                + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
        try {
            RSAKey key = new RSAKeyGenerator(2048)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .issueTime(java.util.Date.from(now))
                    .generate();
            return new SigningKey(
                    kid,
                    JWSAlgorithm.RS256.getName(),
                    key.toPublicJWK().toJSONString(),
                    encryptor.encrypt(key.toJSONString()),
                    now);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not generate an RSA key", ex);
        }
    }

    private static RSAKey parse(String json) {
        try {
            return RSAKey.parse(json);
        } catch (ParseException ex) {
            throw new IllegalStateException("Stored signing key is not a valid RSA JWK", ex);
        }
    }
}
