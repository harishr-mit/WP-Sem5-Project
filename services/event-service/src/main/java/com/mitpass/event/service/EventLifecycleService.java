package com.mitpass.event.service;

import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.NotFoundException;
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
    private final Clock clock;

    public EventLifecycleService(
            EventRepository events,
            EventStateChangeRepository history,
            EventMapper mapper,
            OutboxPublisher outbox,
            Clock clock) {
        this.events = events;
        this.history = history;
        this.mapper = mapper;
        this.outbox = outbox;
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
        Set<EventStatus> allowed = trigger == TransitionTrigger.SCHEDULER ? from.scheduledNext() : from.manualNext();
        event.moveTo(target, allowed);
        checkGuards(event, target);
        Instant now = clock.instant();
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

    /** The creation row of the history ({@code null → DRAFT}), written in the creating transaction. */
    @Transactional
    public void recordCreated(Event event, String actor) {
        history.save(new EventStateChange(
                event, null, event.getStatus(), clock.instant(), actor, TransitionTrigger.MANUAL, null));
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
    private void checkGuards(Event event, EventStatus target) {
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
