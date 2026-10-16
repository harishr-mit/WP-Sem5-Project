package com.mitpass.pass.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code event_ref} from domain events with SQL upserts, so two messages about a new event can't both try to
 * insert it. Messages can arrive out of order after a redelivery (D13), so times and status only move forward: each
 * carries the time it happened, and an older fact never overwrites a newer one.
 */
@Repository
public class EventRefStore {

    private static final String UPSERT_SCHEDULE = """
            INSERT INTO event_ref (event_id, code, title, starts_at, ends_at, schedule_changed_at, updated_at)
            VALUES (:id, :code, :title, :startsAt, :endsAt, :at, :now)
            ON CONFLICT (event_id) DO UPDATE SET
                code = EXCLUDED.code,
                title = CASE WHEN :hasTitle THEN EXCLUDED.title ELSE event_ref.title END,
                starts_at = CASE WHEN event_ref.schedule_changed_at IS NULL
                                   OR EXCLUDED.schedule_changed_at >= event_ref.schedule_changed_at
                                 THEN EXCLUDED.starts_at ELSE event_ref.starts_at END,
                ends_at = CASE WHEN event_ref.schedule_changed_at IS NULL
                                 OR EXCLUDED.schedule_changed_at >= event_ref.schedule_changed_at
                               THEN EXCLUDED.ends_at ELSE event_ref.ends_at END,
                schedule_changed_at = GREATEST(event_ref.schedule_changed_at, EXCLUDED.schedule_changed_at),
                updated_at = EXCLUDED.updated_at""";

    private static final String UPDATE_STATUS = """
            UPDATE event_ref SET status = :status, status_changed_at = :at, updated_at = :now
            WHERE event_id = :id AND (status_changed_at IS NULL OR status_changed_at < :at)""";

    private final JdbcClient jdbc;

    public EventRefStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an event's code, times (as of {@code observedAt}) and, if known, its title. A new event without a title
     * gets its code as title until a message with the title arrives.
     */
    public void upsertSchedule(
            long eventId,
            String code,
            String title,
            Instant startsAt,
            Instant endsAt,
            Instant observedAt,
            Instant now) {
        jdbc.sql(UPSERT_SCHEDULE)
                .param("id", eventId)
                .param("code", code)
                .param("title", title != null ? title : code)
                .param("hasTitle", title != null)
                .param("startsAt", Timestamp.from(startsAt))
                .param("endsAt", Timestamp.from(endsAt))
                .param("at", Timestamp.from(observedAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** @return true if the status was newer than the stored one and was applied */
    public boolean updateStatus(long eventId, String status, Instant changedAt, Instant now) {
        return jdbc.sql(UPDATE_STATUS)
                        .param("id", eventId)
                        .param("status", status)
                        .param("at", Timestamp.from(changedAt))
                        .param("now", Timestamp.from(now))
                        .update()
                > 0;
    }

    /** Next pass sequence for the event; the row lock serializes concurrent issuances for one event. */
    public int nextPassSeq(long eventId) {
        return jdbc.sql("UPDATE event_ref SET last_pass_seq = last_pass_seq + 1 WHERE event_id = :id"
                        + " RETURNING last_pass_seq")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }

    /** Tombstone for a registration cancelled before its pass existed. */
    public void markCancelled(java.util.UUID registrationId, Instant at) {
        jdbc.sql("INSERT INTO cancelled_registration (registration_id, cancelled_at) VALUES (:id, :at)"
                        + " ON CONFLICT DO NOTHING")
                .param("id", registrationId)
                .param("at", Timestamp.from(at))
                .update();
    }

    public boolean isCancelled(java.util.UUID registrationId) {
        return jdbc.sql("SELECT count(*) FROM cancelled_registration WHERE registration_id = :id")
                        .param("id", registrationId)
                        .query(Integer.class)
                        .single()
                > 0;
    }
}
