package com.mitpass.event.idempotency;

import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the result of a request under its {@code Idempotency-Key} (docs/DECISIONS.md D10). {@link #claim} and
 * {@link #complete} run in the caller's business transaction, so the key commits or rolls back with the work.
 *
 * <p>{@code INSERT … ON CONFLICT DO NOTHING} decides who does the work: when two requests with one key arrive together,
 * PostgreSQL makes the second insert wait until the first transaction ends. If it committed, the second finds its
 * stored response and replays it; if it rolled back, the second inserts and does the work itself.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    public static final String MISMATCH = "idempotency-key-mismatch";

    /** A stored result: what the first request returned. */
    public record Stored(int status, String body, String location) {}

    private final JdbcClient jdbc;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyStore(JdbcClient jdbc, IdempotencyProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return empty if this request owns the key and must do the work (then call {@link #complete}); the stored result
     *     if an earlier request with the same key and body already did it
     * @throws ProblemException 422 if the key was used for a different request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Stored> claim(UUID userId, String key, String request) {
        Instant now = clock.instant();
        String hash = sha256(request);
        // An expired key may be reused for a new request.
        jdbc.sql("DELETE FROM idempotency_record WHERE user_id = :user AND idem_key = :key AND expires_at <= :now")
                .param("user", userId)
                .param("key", key)
                .param("now", Timestamp.from(now))
                .update();
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_record (user_id, idem_key, request_hash, created_at, expires_at)
                        VALUES (:user, :key, :hash, :now, :expires)
                        ON CONFLICT DO NOTHING""")
                .param("user", userId)
                .param("key", key)
                .param("hash", hash)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(properties.ttl())))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        Map<String, Object> row =
                jdbc.sql("""
                        SELECT request_hash, response_status, response_body, location
                        FROM idempotency_record WHERE user_id = :user AND idem_key = :key""").param("user", userId).param("key", key).query().singleRow();
        if (!hash.equals(row.get("request_hash"))) {
            throw new ProblemException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemTypes.of(MISMATCH),
                    "Idempotency key reused",
                    "Idempotency-Key '%s' was already used for a different request".formatted(key));
        }
        if (row.get("response_status") == null) {
            throw new ConflictException("A request with Idempotency-Key '%s' is still in progress".formatted(key));
        }
        return Optional.of(
                new Stored(((Number) row.get("response_status")).intValue(), (String) row.get("response_body"), (String)
                        row.get("location")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID userId, String key, int status, String body, String location) {
        jdbc.sql("""
                        UPDATE idempotency_record SET response_status = :status, response_body = :body, location = :location
                        WHERE user_id = :user AND idem_key = :key""")
                .param("status", status)
                .param("body", body)
                .param("location", location)
                .param("user", userId)
                .param("key", key)
                .update();
    }

    @Transactional
    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM idempotency_record WHERE expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    @Scheduled(
            fixedDelayString = "${mitpass.idempotency.cleanup-interval:1h}",
            initialDelayString = "${mitpass.idempotency.cleanup-interval:1h}")
    void cleanup() {
        int deleted = deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency key(s)", deleted);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
