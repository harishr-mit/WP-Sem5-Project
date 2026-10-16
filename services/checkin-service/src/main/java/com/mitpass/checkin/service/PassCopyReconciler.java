package com.mitpass.checkin.service;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.PassDirectory;
import com.mitpass.checkin.client.PassDirectory.PassPage;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.client.PassDirectory.RevocationItem;
import com.mitpass.checkin.client.PassDirectory.RevocationPage;
import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.checkin.service.CopyUpdates.EventWentLive;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the pass copy in step with its owner without anyone pressing a button (docs/plans/phase-5.md §4a, owner Q6:
 * "manual means human error is possible"). Messages do the work in real time; this catches what they missed:
 *
 * <ul>
 *   <li><b>Revocation catch-up</b> (every 60 s): the pass-service revocation feed from a stored cursor. Closes the unsafe
 *       gap, a lost {@code PassRevoked}.
 *   <li><b>Scanning-window sync</b> (every 5 min, and when an event goes LIVE): every event that is LIVE or starts within
 *       3 h gets its status re-read from event-service and all its passes re-read from pass-service. Closes passes
 *       issued before this service's queue existed, lost {@code PassIssued} / {@code EventStateChanged}.
 *   <li><b>On demand</b> during a scan: one pass fetched by id, or (typed numbers) its event re-synced, rate-limited.
 * </ul>
 *
 * Calls to pass-service use this service's own service token (§4b). Each scheduled job holds a PostgreSQL advisory lock,
 * so two instances never run the same job at once (ShedLock comes in P7). The owner always wins, except that REVOKED
 * never turns back into VALID.
 */
@Component
public class PassCopyReconciler {

    private static final Logger log = LoggerFactory.getLogger(PassCopyReconciler.class);
    static final String REVOCATIONS = "revocations";
    private static final long REVOCATIONS_LOCK = 0x4D49545041535301L; // "MITPASS" + 1
    private static final long WINDOW_LOCK = 0x4D49545041535302L;

    /** What one event sync saw. */
    public record SyncReport(long eventId, int passes, int revoked) {}

    private final PassDirectory directory;
    private final EventCatalog catalog;
    private final PassCopyStore passes;
    private final EventRefStore events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CheckinProperties properties;
    private final Clock clock;
    private final TaskExecutor executor;
    private final Map<Long, Instant> lastOnDemand = new ConcurrentHashMap<>();

    public PassCopyReconciler(
            PassDirectory directory,
            EventCatalog catalog,
            PassCopyStore passes,
            EventRefStore events,
            JdbcClient jdbc,
            TransactionTemplate tx,
            CheckinProperties properties,
            Clock clock,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.directory = directory;
        this.catalog = catalog;
        this.passes = passes;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
    }

    // ---- scheduled jobs (registered in AppConfig with the configured intervals) ------------------------------------

    /** Applies every revocation after the stored cursor. Returns how many were applied (-1: another instance runs). */
    public int catchUpRevocations() {
        Integer applied = tx.execute(status -> {
            if (!tryLock(REVOCATIONS_LOCK)) {
                return -1;
            }
            long cursor = cursor(REVOCATIONS);
            int count = 0;
            while (true) {
                RevocationPage page = directory.revocationsSince(cursor, 500);
                for (RevocationItem item : page.items()) {
                    passes.markRevoked(
                            item.passId(), item.eventId(), null, item.revokedAt(), item.reason(), clock.instant());
                    count++;
                }
                if (page.items().isEmpty() || page.nextSince() <= cursor) {
                    break;
                }
                cursor = page.nextSince();
                saveCursor(REVOCATIONS, cursor);
            }
            return count;
        });
        if (applied != null && applied > 0) {
            log.info("Revocation catch-up applied {} revocation(s)", applied);
        }
        return applied == null ? 0 : applied;
    }

    /** Re-reads status and passes of every event in the scanning window. Returns the events synced (-1: locked). */
    public int syncWindow() {
        Integer synced = tx.execute(status -> {
            if (!tryLock(WINDOW_LOCK)) {
                return -1;
            }
            Instant now = clock.instant();
            int count = 0;
            for (EventInfo event :
                    catalog.scanningWindow(now, now.plus(properties.reconcile().windowAhead()))) {
                events.apply(event.id(), event.code(), event.status(), now, now);
                syncEvent(event.id());
                count++;
            }
            return count;
        });
        return synced == null ? 0 : synced;
    }

    /** Copies every pass of one event from pass-service (the admin "sync" action uses it too). */
    public SyncReport syncEvent(long eventId) {
        int seen = 0;
        int revoked = 0;
        int page = 0;
        PassPage result;
        do {
            result = directory.passesOf(eventId, page, properties.reconcile().pageSize());
            for (PassRecord record : result.content()) {
                passes.upsert(toRef(record), clock.instant());
                seen++;
                revoked += record.revoked() ? 1 : 0;
            }
            page++;
        } while (page < result.totalPages());
        return new SyncReport(eventId, seen, revoked);
    }

    // ---- on demand -------------------------------------------------------------------------------------------------

    /** During a scan: a genuine token for a pass the copy lacks. Never throws (pass-service down → still unknown). */
    public void fetchPass(UUID passId) {
        try {
            directory.pass(passId).ifPresent(record -> passes.upsert(toRef(record), clock.instant()));
        } catch (RuntimeException ex) {
            log.warn("Could not fetch pass {} from pass-service: {}", passId, ex.getMessage());
        }
    }

    /** During a scan: a typed number the copy lacks. Re-syncs its event, at most once per {@code on-demand-min-interval}. */
    public void syncEventSoon(long eventId) {
        Instant now = clock.instant();
        Instant last = lastOnDemand.get(eventId);
        if (last != null && now.isBefore(last.plus(properties.reconcile().onDemandMinInterval()))) {
            return;
        }
        lastOnDemand.put(eventId, now);
        try {
            syncEvent(eventId);
        } catch (RuntimeException ex) {
            log.warn("Could not sync event {} from pass-service: {}", eventId, ex.getMessage());
        }
    }

    /** An event went LIVE: sync its passes now, off the consumer's thread and after its commit. */
    @TransactionalEventListener
    public void onEventLive(EventWentLive live) {
        executor.execute(() -> {
            try {
                syncEvent(live.eventId());
            } catch (RuntimeException ex) {
                log.warn("Sync after LIVE failed for event {}: {}", live.eventId(), ex.getMessage());
            }
        });
    }

    /** Scheduled entry points: log and carry on, the next run tries again. */
    public void scheduledRevocations() {
        try {
            catchUpRevocations();
        } catch (RuntimeException ex) {
            log.warn("Revocation catch-up failed (retrying next run): {}", ex.getMessage());
        }
    }

    public void scheduledWindow() {
        try {
            syncWindow();
        } catch (RuntimeException ex) {
            log.warn("Scanning-window sync failed (retrying next run): {}", ex.getMessage());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private boolean tryLock(long key) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", key)
                .query(Boolean.class)
                .single());
    }

    long cursor(String name) {
        return jdbc.sql("SELECT position FROM sync_cursor WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private void saveCursor(String name, long position) {
        jdbc.sql("""
                        INSERT INTO sync_cursor (name, position, updated_at) VALUES (:name, :position, :now)
                        ON CONFLICT (name) DO UPDATE SET position = EXCLUDED.position, updated_at = EXCLUDED.updated_at
                        """)
                .param("name", name)
                .param("position", position)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
    }

    private static PassRef toRef(PassRecord record) {
        return new PassRef(
                record.id(),
                record.passNumber(),
                record.eventId(),
                record.registrationId(),
                record.userId(),
                record.holderName(),
                record.holderEmail(),
                record.revoked() ? PassRef.REVOKED : PassRef.VALID,
                record.issuedAt(),
                record.revokedAt(),
                record.revokeReason());
    }
}
