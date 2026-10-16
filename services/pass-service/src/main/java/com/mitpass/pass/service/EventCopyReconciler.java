package com.mitpass.pass.service;

import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.pass.client.EventCatalogClient;
import com.mitpass.pass.client.EventCatalogClient.EventInfo;
import com.mitpass.pass.domain.EventRef;
import com.mitpass.pass.repository.EventRefRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps pass-service's copy of event status and times in step with event-service, the owner (D14, REVIEW-LATER RL7,
 * owner 2026-10-16: no manual steps). Messages do this in real time; every few minutes this job re-reads, from the
 * public catalogue, each event that still has valid passes. A difference is applied through the same handlers the
 * messages use, so a lost {@code EventStateChanged(CANCELLED)} still revokes the passes (saga step, trigger SYSTEM) and a
 * lost {@code EventRescheduled} still moves unlock and expiry times. Running twice is harmless: the handlers ignore
 * what is already applied.
 */
@Component
public class EventCopyReconciler {

    private static final Logger log = LoggerFactory.getLogger(EventCopyReconciler.class);
    static final String ACTOR = "reconciler";

    private final JdbcClient jdbc;
    private final EventCatalogClient catalog;
    private final EventRefRepository events;
    private final PassLifecycleService lifecycle;
    private final Clock clock;

    public EventCopyReconciler(
            JdbcClient jdbc,
            EventCatalogClient catalog,
            EventRefRepository events,
            PassLifecycleService lifecycle,
            Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.events = events;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${mitpass.passes.reconcile.interval:5m}",
            initialDelayString = "${mitpass.passes.reconcile.initial-delay:30s}")
    public void run() {
        runOnce();
    }

    /** @return events whose copy was corrected */
    public int runOnce() {
        List<Long> ids = jdbc.sql("SELECT DISTINCT event_id FROM pass WHERE status IN ('ISSUED', 'ACTIVE')")
                .query(Long.class)
                .list();
        int corrected = 0;
        for (long id : ids) {
            try {
                corrected += reconcile(id) ? 1 : 0;
            } catch (NotFoundException ex) {
                log.debug("Event {} is unknown to event-service; skipped", id);
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile event {} (retrying next run): {}", id, ex.getMessage());
            }
        }
        if (corrected > 0) {
            log.info("Event copy reconciler corrected {} event(s)", corrected);
        }
        return corrected;
    }

    private boolean reconcile(long id) {
        catalog.evict(id); // read the owner now, not a cached answer
        EventInfo owner = catalog.get(id);
        EventRef copy = events.findById(id).orElse(null);
        if (copy == null) {
            return false;
        }
        Instant now = clock.instant();
        boolean changed = false;
        if (!Objects.equals(owner.startsAt(), copy.getStartsAt())
                || !Objects.equals(owner.endsAt(), copy.getEndsAt())) {
            lifecycle.eventRescheduled(
                    new EventRescheduled(id, owner.code(), owner.startsAt(), owner.endsAt(), now, ACTOR));
            changed = true;
        }
        if (owner.status() != null && !owner.status().equals(copy.getStatus())) {
            log.info("Event {}: copy says {}, event-service says {}", id, copy.getStatus(), owner.status());
            lifecycle.eventStateChanged(new EventStateChanged(
                    id,
                    owner.code(),
                    copy.getStatus(),
                    owner.status(),
                    now,
                    ACTOR,
                    "RECONCILER",
                    owner.startsAt(),
                    owner.endsAt()));
            changed = true;
        }
        return changed;
    }
}
