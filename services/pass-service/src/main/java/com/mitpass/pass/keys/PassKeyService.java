package com.mitpass.pass.keys;

import com.mitpass.common.security.keys.KeyEncryptor;
import com.mitpass.pass.config.PassProperties;
import com.mitpass.pass.domain.SigningKey;
import com.mitpass.pass.repository.SigningKeyRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
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
 * The ES256 keys that sign pass tokens (docs/DECISIONS.md D12), separate from auth-service's RS256 keys. Same pattern
 * as auth-service: the first key is created on demand, at most one is ACTIVE, the private half is stored encrypted.
 *
 * <p>A retired key stays in the JWK set while a token it signed can still be valid: live tokens for their lifetime
 * plus a grace period after the rotation, static tokens until the latest {@code exp} the key signed
 * ({@code max_token_exp}) plus the same grace.
 */
@Service
public class PassKeyService {

    static final Duration GRACE = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(PassKeyService.class);
    private static final DateTimeFormatter KID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SigningKeyRepository keys;
    private final KeyEncryptor encryptor;
    private final Clock clock;
    private final Duration liveTokenTtl;
    private final TransactionTemplate newTransaction;
    private final Map<String, ECKey> decrypted = new ConcurrentHashMap<>();

    public PassKeyService(
            SigningKeyRepository keys,
            KeyEncryptor encryptor,
            Clock clock,
            PassProperties properties,
            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.encryptor = encryptor;
        this.clock = clock;
        this.liveTokenTtl = properties.liveTokenTtl();
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The private key that signs new pass tokens. Created (and stored encrypted) on first use. */
    public ECKey activeKey() {
        SigningKey key = keys.findByStatus(SigningKey.Status.ACTIVE).orElseGet(this::createFirstKey);
        return decrypted.computeIfAbsent(key.getKid(), kid -> parse(encryptor.decrypt(key.getPrivateKeyEnc())));
    }

    /** Public keys that verify currently valid pass tokens, newest first. */
    public JWKSet publicJwkSet() {
        activeKey();
        Instant now = clock.instant();
        List<JWK> published = keys.findPublishable(now.minus(liveTokenTtl).minus(GRACE), now.minus(GRACE)).stream()
                .map(key -> (JWK) parse(key.getPublicJwk()))
                .toList();
        return new JWKSet(published);
    }

    /** Remembers that {@code kid} signed a token valid until {@code exp} (in the caller's transaction). */
    @Transactional
    public void recordTokenExpiry(String kid, Instant exp) {
        keys.raiseMaxTokenExp(kid, exp);
    }

    /** Retires the active key and makes a new one; tokens signed by the old key stay verifiable until they expire. */
    @Transactional
    public String rotate() {
        Instant now = clock.instant();
        keys.findByStatus(SigningKey.Status.ACTIVE).ifPresent(current -> {
            current.retire(now);
            keys.saveAndFlush(current);
        });
        SigningKey next = keys.save(newKey(now));
        log.info("Pass signing key rotated: new kid {}", next.getKid());
        return next.getKid();
    }

    private SigningKey createFirstKey() {
        try {
            SigningKey created = newTransaction.execute(status -> keys.saveAndFlush(newKey(clock.instant())));
            log.info("Created pass signing key {}", created.getKid());
            return created;
        } catch (DataIntegrityViolationException race) {
            // Another instance created one at the same moment: the partial unique index kept only theirs.
            return keys.findByStatus(SigningKey.Status.ACTIVE).orElseThrow(() -> race);
        }
    }

    private SigningKey newKey(Instant now) {
        String kid = "pass-" + KID_TIME.format(now) + "-"
                + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
        try {
            ECKey key = new ECKeyGenerator(Curve.P_256)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .issueTime(Date.from(now))
                    .generate();
            return new SigningKey(
                    kid,
                    JWSAlgorithm.ES256.getName(),
                    key.toPublicJWK().toJSONString(),
                    encryptor.encrypt(key.toJSONString()),
                    now);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not generate an EC key", ex);
        }
    }

    private static ECKey parse(String json) {
        try {
            return ECKey.parse(json);
        } catch (ParseException ex) {
            throw new IllegalStateException("Stored signing key is not a valid EC JWK", ex);
        }
    }
}
