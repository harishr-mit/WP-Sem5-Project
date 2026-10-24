package com.mitpass.checkin.service;

import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.AttendanceVoided;
import com.mitpass.common.web.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Voids wrong check-ins (RL12, D17): the event's organizer or an admin; volunteers can't. Under the same presence row
 * lock as a scan, so a void and a check-in of the same pass never interleave. The rows move to
 * {@code voided_attendance}, presence goes back (NOT_ARRIVED, or OUT if another session's check-in remains), and
 * {@code AttendanceVoided} goes out in the same transaction.
 */
@Service
public class CheckInVoids {

    /** What was voided; {@code stillCheckedIn}: another session's check-in of the pass remains. */
    public record Voided(UUID passId, List<Long> sessionIds, boolean stillCheckedIn) {}

    private final JdbcClient jdbc;
    private final OutboxPublisher outbox;
    private final LiveStats live;
    private final Clock clock;

    public CheckInVoids(JdbcClient jdbc, OutboxPublisher outbox, LiveStats live, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.live = live;
        this.clock = clock;
    }

    /** @param sessionId one session, or null for every check-in of the pass at this event */
    @Transactional
    public Voided voidCheckIns(long eventId, UUID passId, Long sessionId, String reason, String actor) {
        String state = jdbc.sql("SELECT state FROM presence WHERE pass_id = :pass AND event_id = :event FOR UPDATE")
                .param("pass", passId)
                .param("event", eventId)
                .query(String.class)
                .optional()
                .orElseThrow(
                        () -> new NotFoundException("Pass %s has no check-in at event %d".formatted(passId, eventId)));
        String which =
                "event_id = :event AND pass_id = :pass AND (CAST(:session AS BIGINT) IS NULL OR session_id = :session)";
        List<Long> sessions = jdbc.sql("SELECT session_id FROM attendance WHERE " + which + " ORDER BY session_id")
                .param("event", eventId)
                .param("pass", passId)
                .param("session", sessionId)
                .query(Long.class)
                .list();
        if (sessions.isEmpty()) {
            throw new NotFoundException("Pass %s has no check-in to void at event %d".formatted(passId, eventId));
        }
        Instant now = clock.instant();
        Long gateId = jdbc.sql("SELECT gate_id FROM attendance WHERE " + which + " LIMIT 1")
                .param("event", eventId)
                .param("pass", passId)
                .param("session", sessionId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO voided_attendance (id, pass_id, event_id, session_id, gate_id, gate_code, volunteer_id,
                                                       method, checked_in_at, scanned_at, voided_at, voided_by, void_reason)
                        SELECT id, pass_id, event_id, session_id, gate_id, gate_code, volunteer_id, method, checked_in_at,
                               scanned_at, :now, :actor, :reason
                        FROM attendance WHERE """ + " " + which)
                .param("event", eventId)
                .param("pass", passId)
                .param("session", sessionId)
                .param("now", Timestamp.from(now))
                .param("actor", actor)
                .param("reason", reason)
                .update();
        jdbc.sql("DELETE FROM attendance WHERE " + which)
                .param("event", eventId)
                .param("pass", passId)
                .param("session", sessionId)
                .update();

        boolean still = jdbc.sql("SELECT count(*) FROM attendance WHERE event_id = :event AND pass_id = :pass")
                        .param("event", eventId)
                        .param("pass", passId)
                        .query(Long.class)
                        .single()
                > 0;
        String back = still ? "OUT" : "NOT_ARRIVED";
        jdbc.sql("UPDATE presence SET state = :state, changed_at = :now, version = version + 1 WHERE pass_id = :pass")
                .param("state", back)
                .param("now", Timestamp.from(now))
                .param("pass", passId)
                .update();
        jdbc.sql("""
                        INSERT INTO presence_history (pass_id, from_state, to_state, session_id, gate_id, scan_id, actor,
                                                      changed_at)
                        VALUES (:pass, :from, :to, :session, :gate, :scan, :actor, :now)""")
                .param("pass", passId)
                .param("from", state)
                .param("to", back)
                .param("session", sessions.getFirst())
                .param("gate", gateId)
                .param("scan", UUID.randomUUID())
                .param("actor", actor)
                .param("now", Timestamp.from(now))
                .update();

        outbox.publish(
                CheckinEvents.AGGREGATE,
                passId.toString(),
                new AttendanceVoided(passId, eventId, sessions, still, now, actor, reason));
        // The dashboard's Redis counters are a cache of attendance (D16): drop them, the next read rebuilds.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                live.forget(eventId);
            }
        });
        return new Voided(passId, sessions, still);
    }
}
