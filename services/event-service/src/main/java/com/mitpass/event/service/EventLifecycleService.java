package com.mitpass.event.service;

import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.event.client.CheckinClient;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStateChange;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.mapper.EventMapper;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.EventStateChangeRepository;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.StateChangeResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves events through their lifecycle (PROJECT.md §3, docs/DECISIONS.md D10). Each move is one transaction: the new
 * status, its history row (and, from T05.7, its {@code EventStateChanged} outbox row) commit together. The event's
 * {@code @Version} makes a concurrent edit or a second scheduler instance fail with an optimistic-lock error instead of
 * applying a move twice.
 */
@Service
@Transactional(readOnly = true)
public class EventLifecycleService {

    private final EventRepository events;
    private final EventStateChangeRepository history;
    private final EventMapper mapper;
    private final OutboxPublisher outbox;
    private final CheckinClient checkins;
    private final Clock clock;

    public EventLifecycleService(
            EventRepository events,
            EventStateChangeRepository history,
            EventMapper mapper,
            OutboxPublisher outbox,
            CheckinClient checkins,
            Clock clock) {
        this.events = events;
        this.history = history;
        this.mapper = mapper;
        this.outbox = outbox;
        this.checkins = checkins;
        this.clock = clock;
    }

    /**
     * @param actor user id of the caller, or {@code "system"} for the scheduler
     * @param trigger MANUAL moves may not start an event (LIVE); SCHEDULER moves are limited to the time-based ones
     */
    @Transactional
    public EventDetailResponse transition(
            long id, EventStatus target, String actor, TransitionTrigger trigger, String reason) {
        Event event = events.findDetailedById(id).orElseThrow(() -> NotFoundException.of("Event", id));
        EventStatus from = event.getStatus();
        Set<EventStatus> allowed = switch (trigger) {
            case SCHEDULER -> from.scheduledNext();
            case ADMIN -> event.adminTransitions();
            default -> from.manualNext();
        };
        event.moveTo(target, allowed);
        checkGuards(event, from, target);
        Instant now = clock.instant();
        if (trigger == TransitionTrigger.ADMIN) {
            applyOverride(event, from, target, now, actor);
        }
        history.save(new EventStateChange(event, from, target, now, actor, trigger, reason));
        outbox.publish(
                "Event",
                String.valueOf(id),
                new EventStateChanged(
                        id,
                        event.getCode(),
                        from.name(),
                        target.name(),
                        now,
                        actor,
                        trigger.name(),
                        event.getStartsAt(),
                        event.getEndsAt()));
        return mapper.toDetail(events.saveAndFlush(event));
    }

    /** MANUAL when an organizer could make this move too, ADMIN when only the override allows it (RL10). */
    @Transactional(readOnly = true)
    public TransitionTrigger triggerForAdmin(long id, EventStatus target) {
        Event event = events.findById(id).orElseThrow(() -> NotFoundException.of("Event", id));
        return event.getStatus().manualNext().contains(target) ? TransitionTrigger.MANUAL : TransitionTrigger.ADMIN;
    }

    /**
     * Start now: an event forced LIVE before its start time starts now, so passes unlock and the times stay true
     * (EventRescheduled). Reopen: only while the end time is still ahead, or the scheduler would end it again at once.
     */
    private void applyOverride(Event event, EventStatus from, EventStatus target, Instant now, String actor) {
        if (from == EventStatus.COMPLETED && !event.getEndsAt().isAfter(now)) {
            throw new BusinessRuleException("The event ended at %s: move its end time later first, then reopen it"
                    .formatted(event.getEndsAt()));
        }
        if (from == EventStatus.REGISTRATION_CLOSED
                && target == EventStatus.LIVE
                && event.getStartsAt().isAfter(now)) {
            Instant start = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            event.setStartsAt(start);
            // Registration can't end after the start (ck_event_registration_*): it ends now, and an opening time
            // that would come after that is dropped.
            if (event.getRegistrationClosesAt() != null
                    && event.getRegistrationClosesAt().isAfter(start)) {
                event.setRegistrationClosesAt(start);
            }
            Instant closes = event.getRegistrationClosesAt() != null ? event.getRegistrationClosesAt() : start;
            if (event.getRegistrationOpensAt() != null
                    && !event.getRegistrationOpensAt().isBefore(closes)) {
                event.setRegistrationOpensAt(null);
            }
            recordRescheduled(event, actor);
        }
    }

    /** The creation row of the history ({@code null → DRAFT}), written in the creating transaction. */
    @Transactional
    public void recordCreated(Event event, String actor) {
        history.save(new EventStateChange(
                event, null, event.getStatus(), clock.instant(), actor, TransitionTrigger.MANUAL, null));
    }

    /**
     * The event's start or end time changed (owner decision 2026-10-16, D13): pass-service moves its unlock and expiry
     * times. DRAFT events have no registrations or passes, so nothing is published for them.
     */
    @Transactional
    public void recordRescheduled(Event event, String actor) {
        if (event.getStatus() == EventStatus.DRAFT) {
            return;
        }
        outbox.publish(
                "Event",
                String.valueOf(event.getId()),
                new EventRescheduled(
                        event.getId(),
                        event.getCode(),
                        event.getStartsAt(),
                        event.getEndsAt(),
                        clock.instant(),
                        actor));
    }

    public List<StateChangeResponse> history(long id) {
        if (!events.existsById(id)) {
            throw NotFoundException.of("Event", id);
        }
        return history.findByEventIdOrderByChangedAtAscIdAsc(id).stream()
                .map(StateChangeResponse::from)
                .toList();
    }

    /** Rules the table can't express, checked after the table allowed the move. */
    private void checkGuards(Event event, EventStatus from, EventStatus target) {
        if (from == EventStatus.LIVE
                && target == EventStatus.REGISTRATION_CLOSED
                && checkins.hasCheckIns(event.getId())) {
            // RL3 (D17): the undo is for an accidental early start, before anyone came in.
            throw new ConflictException(
                    "The event can't go back: people have already checked in. End it instead, or void wrong check-ins.");
        }
        if (target != EventStatus.REGISTRATION_OPEN) {
            return;
        }
        Instant now = clock.instant();
        if (!event.getStartsAt().isAfter(now)) {
            throw new BusinessRuleException("Registration can't open: the event has already started");
        }
        if (event.getRegistrationClosesAt() != null
                && !event.getRegistrationClosesAt().isAfter(now)) {
            throw new BusinessRuleException("Registration can't open: its closing time (%s) has passed"
                    .formatted(event.getRegistrationClosesAt()));
        }
    }
}
