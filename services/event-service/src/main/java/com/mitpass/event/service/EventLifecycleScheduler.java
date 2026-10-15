package com.mitpass.event.service;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.IllegalTransitionException;
import com.mitpass.event.config.LifecycleSchedulerProperties;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.repository.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-based lifecycle moves (docs/plans/phase-3.md §5, docs/DECISIONS.md D10): open and close registration, start the
 * event (LIVE is set only here) and complete it. Each move runs in its own transaction through
 * {@link EventLifecycleService}; an event that changed meanwhile (optimistic lock) or no longer allows the move is
 * skipped, so a second instance or a simultaneous button press never applies a move twice. ShedLock comes in P7.
 *
 * <p>The bean always exists (tests call {@link #runOnce(Instant)}); only the timer is switched off by
 * {@code mitpass.events.scheduler.enabled=false}.
 */
@Component
public class EventLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventLifecycleScheduler.class);

    private final EventRepository events;
    private final EventLifecycleService lifecycle;
    private final LifecycleSchedulerProperties properties;
    private final Clock clock;

    public EventLifecycleScheduler(
            EventRepository events,
            EventLifecycleService lifecycle,
            LifecycleSchedulerProperties properties,
            Clock clock) {
        this.events = events;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${mitpass.events.scheduler.interval:30s}",
            initialDelayString = "${mitpass.events.scheduler.interval:30s}")
    void tick() {
        if (properties.enabled()) {
            runOnce(clock.instant());
        }
    }

    /** Applies every move that is due at {@code now}; returns how many were applied. */
    public int runOnce(Instant now) {
        int moved = 0;
        moved += apply(events.findIdsDueToOpen(now), EventStatus.REGISTRATION_OPEN, "Registration opening time");
        moved += apply(events.findIdsDueToClose(now), EventStatus.REGISTRATION_CLOSED, "Registration closing time");
        moved += apply(
                events.findIdsDueToStart(now.plus(properties.doorsOpenLead())),
                EventStatus.LIVE,
                "Doors open " + properties.doorsOpenLead().toMinutes() + " min before the start");
        moved += apply(events.findIdsDueToComplete(now), EventStatus.COMPLETED, "Event ended");
        return moved;
    }

    private int apply(List<Long> ids, EventStatus target, String reason) {
        int moved = 0;
        for (Long id : ids) {
            try {
                lifecycle.transition(id, target, "system", TransitionTrigger.SCHEDULER, reason);
                log.info("Event {} moved to {} by the scheduler", id, target);
                moved++;
            } catch (OptimisticLockingFailureException | IllegalTransitionException | BusinessRuleException ex) {
                log.debug("Skipped event {} -> {}: {}", id, target, ex.getMessage());
            }
        }
        return moved;
    }
}
