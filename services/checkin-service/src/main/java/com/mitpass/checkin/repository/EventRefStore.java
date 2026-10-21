package com.mitpass.checkin.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The copy of event status (D14). Messages apply only if newer than what is stored ({@code changedAt}); the
 * reconciler's read of the owner applies only if no newer message arrived meanwhile.
 */
@Repository
public class EventRefStore {

    private final JdbcClient jdbc;

    public EventRefStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores {@code status} as of {@code asOf} (a message's {@code changedAt}, or the moment the owner was read), unless
     * the stored status is from later. Returns whether the row changed.
     */
    public boolean apply(long eventId, String code, String status, Instant asOf, Instant now) {
        return jdbc.sql("""
                        INSERT INTO event_ref (event_id, code, status, status_changed_at, updated_at)
                        VALUES (:id, :code, :status, :asOf, :now)
                        ON CONFLICT (event_id) DO UPDATE SET
                            code = EXCLUDED.code,
                            status = EXCLUDED.status,
                            status_changed_at = EXCLUDED.status_changed_at,
                            updated_at = EXCLUDED.updated_at
                        WHERE event_ref.status_changed_at IS NULL
                           OR event_ref.status_changed_at <= EXCLUDED.status_changed_at
                        """)
                        .param("id", eventId)
                        .param("code", code)
                        .param("status", status)
                        .param("asOf", PassCopyStore.ts(asOf))
                        .param("now", PassCopyStore.ts(now))
                        .update()
                > 0;
    }

    /** When the status copy last changed (the owner's time), e.g. when the event was completed. */
    public Optional<Instant> statusChangedAt(long eventId) {
        return jdbc.sql("SELECT status_changed_at FROM event_ref WHERE event_id = :id")
                .param("id", eventId)
                .query((rs, row) -> Optional.ofNullable(rs.getTimestamp(1)).map(java.sql.Timestamp::toInstant))
                .optional()
                .flatMap(o -> o);
    }

    public Optional<String> status(long eventId) {
        return jdbc.sql("SELECT status FROM event_ref WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .optional();
    }
}
