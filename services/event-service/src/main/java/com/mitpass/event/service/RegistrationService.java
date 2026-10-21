package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.Registration;
import com.mitpass.event.domain.RegistrationChange;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.idempotency.IdempotencyStore;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.RegistrationChangeRepository;
import com.mitpass.event.repository.RegistrationRepository;
import com.mitpass.event.web.dto.RegistrationRequests.EligibilityResponse;
import com.mitpass.event.web.dto.RegistrationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registering and cancelling (docs/plans/phase-3.md §8, docs/DECISIONS.md D10). Every write is one transaction that
 * holds the registration, the seat counter, the history rows and the outbox rows together.
 *
 * <p>Seats: the fast path is one conditional UPDATE ({@link EventRepository#claimSeat}). When it finds the event full,
 * the slow path locks the event row ({@code PESSIMISTIC_WRITE}) and checks again before waitlisting, because a
 * cancellation may have freed a seat in between. Cancellations take the same lock first, so "free a seat" and "join
 * the waitlist" can never pass each other: nobody waits while a seat is free.
 */
@Service
@Transactional(readOnly = true)
public class RegistrationService {

    private final RegistrationRepository registrations;
    private final RegistrationChangeRepository history;
    private final EventRepository events;
    private final EligibilityPolicy eligibility;
    private final WaitlistService waitlist;
    private final RegistrationEvents outbox;
    private final EntityManager entityManager;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;
    private final Clock clock;

    public RegistrationService(
            RegistrationRepository registrations,
            RegistrationChangeRepository history,
            EventRepository events,
            EligibilityPolicy eligibility,
            WaitlistService waitlist,
            RegistrationEvents outbox,
            EntityManager entityManager,
            IdempotencyStore idempotency,
            JsonMapper json,
            Clock clock) {
        this.registrations = registrations;
        this.history = history;
        this.events = events;
        this.eligibility = eligibility;
        this.waitlist = waitlist;
        this.outbox = outbox;
        this.entityManager = entityManager;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    /** The result of {@link #register(CurrentUser, long, String)}; {@code replayed} = stored earlier, nothing done now. */
    public record RegisterOutcome(RegistrationResponse response, boolean replayed) {}

    /**
     * Registers with an optional {@code Idempotency-Key}: the key, the registration and its outbox rows commit
     * together, and a retry with the same key and body gets the first response back instead of a second registration
     * (docs/DECISIONS.md D10).
     */
    @Transactional
    public RegisterOutcome register(CurrentUser student, long eventId, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<IdempotencyStore.Stored> stored =
                    idempotency.claim(student.id(), idempotencyKey, "POST /api/registrations eventId=" + eventId);
            if (stored.isPresent()) {
                return new RegisterOutcome(json.readValue(stored.get().body(), RegistrationResponse.class), true);
            }
        }
        RegistrationResponse response = register(student, eventId);
        if (idempotencyKey != null) {
            idempotency.complete(
                    student.id(),
                    idempotencyKey,
                    201,
                    json.writeValueAsString(response),
                    "/api/registrations/" + response.id());
        }
        return new RegisterOutcome(response, false);
    }

    @Transactional
    public RegistrationResponse register(CurrentUser student, long eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> NotFoundException.of("Event", eventId));
        Instant now = clock.instant();
        Eligibility check = eligibility.check(event, student, now);
        if (!check.registrationOpen()) {
            throw RegistrationProblems.registrationClosed(check.reasons().get(0));
        }
        if (!check.eligible()) {
            throw RegistrationProblems.notEligible(check.reasons());
        }
        if (registrations.existsActive(eventId, student.id())) {
            throw RegistrationProblems.alreadyRegistered(eventId);
        }

        Registration registration = new Registration(event, student);
        try {
            registrations.saveAndFlush(registration);
        } catch (DataIntegrityViolationException ex) {
            // Two requests of one student passed the pre-check together; the partial unique index stops the second.
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uk_registration_active")) {
                throw RegistrationProblems.alreadyRegistered(eventId);
            }
            throw ex;
        }
        String actor = student.id().toString();
        record(registration, null, RegistrationStatus.PENDING, actor, TransitionTrigger.MANUAL, null);

        if (events.claimSeat(eventId) == 1) {
            confirm(registration, now, actor);
        } else {
            // Slow path: lock the event row, then look again with fresh state.
            entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
            if (event.getStatus() != EventStatus.REGISTRATION_OPEN) {
                throw RegistrationProblems.registrationClosed(
                        "Registration is not open (the event is %s)".formatted(event.getStatus()));
            }
            if (events.claimSeat(eventId) == 1) {
                confirm(registration, now, actor);
            } else {
                registration.waitlist(now, registrations.nextWaitlistSeq());
                record(
                        registration,
                        RegistrationStatus.PENDING,
                        RegistrationStatus.WAITLISTED,
                        actor,
                        TransitionTrigger.MANUAL,
                        "Event full");
                registrations.flush(); // the position query below counts this row
                outbox.waitlisted(
                        registration, (int) registrations.waitlistPosition(eventId, registration.getWaitlistSeq()));
            }
        }
        registrations.flush();
        return toResponse(registration, null);
    }

    /**
     * Cancels a registration. A cancelled CONFIRMED seat goes straight to the first waitlisted student, in this same
     * transaction; with nobody waiting it is freed.
     *
     * @param actor user id of whoever cancels (the student, the event's organizer, or an admin)
     */
    @Transactional
    public RegistrationResponse cancel(UUID id, String actor, String reason) {
        Registration registration = registrations.findWithEventById(id).orElseThrow(() -> notFound(id));
        Event event = registration.getEvent();
        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(registration); // the latest committed status, now that cancellations are serialized

        if (event.getStatus() != EventStatus.REGISTRATION_OPEN
                && event.getStatus() != EventStatus.REGISTRATION_CLOSED) {
            throw RegistrationProblems.registrationClosed(
                    "Cancellations are closed: the event is %s".formatted(event.getStatus()));
        }
        RegistrationStatus previous = registration.getStatus();
        registration.cancel(clock.instant(), actor);
        record(registration, previous, RegistrationStatus.CANCELLED, actor, TransitionTrigger.MANUAL, reason);
        outbox.cancelled(registration, previous);

        UUID promoted = null;
        if (previous == RegistrationStatus.CONFIRMED) {
            Optional<Registration> next = waitlist.promoteNext(event.getId(), true, registration.getId());
            if (next.isPresent()) {
                promoted = next.get().getId();
            } else {
                events.releaseSeat(event.getId());
            }
        }
        registrations.flush();
        return toResponse(registration, promoted);
    }

    public RegistrationResponse get(UUID id) {
        return toResponse(registrations.findWithEventById(id).orElseThrow(() -> notFound(id)), null);
    }

    public PageResponse<RegistrationResponse> mine(UUID userId, RegistrationStatus status, Pageable pageable) {
        return PageResponse.from(registrations.findByUser(userId, status, pageable), r -> toResponse(r, null));
    }

    public PageResponse<RegistrationResponse> forEvent(long eventId, RegistrationStatus status, Pageable pageable) {
        if (!events.existsById(eventId)) {
            throw NotFoundException.of("Event", eventId);
        }
        return PageResponse.from(registrations.findByEvent(eventId, status, pageable), r -> toResponse(r, null));
    }

    public EligibilityResponse eligibility(long eventId, CurrentUser student) {
        Event event = events.findDetailedById(eventId).orElseThrow(() -> NotFoundException.of("Event", eventId));
        Eligibility check = eligibility.check(event, student, clock.instant());
        RegistrationResponse mine = registrations
                .findActive(eventId, student.id())
                .map(r -> toResponse(r, null))
                .orElse(null);
        return new EligibilityResponse(
                check.eligible(),
                check.registrationOpen(),
                check.reasons(),
                event.getSeatsLeft(),
                registrations.countByEventAndStatus(eventId, RegistrationStatus.WAITLISTED),
                mine);
    }

    private void confirm(Registration registration, Instant now, String actor) {
        registration.confirm(now, false);
        record(
                registration,
                RegistrationStatus.PENDING,
                RegistrationStatus.CONFIRMED,
                actor,
                TransitionTrigger.MANUAL,
                null);
        outbox.confirmed(registration);
    }

    private void record(
            Registration registration,
            RegistrationStatus from,
            RegistrationStatus to,
            String actor,
            TransitionTrigger trigger,
            String reason) {
        history.save(new RegistrationChange(registration, from, to, clock.instant(), actor, trigger, reason));
    }

    RegistrationResponse toResponse(Registration r, UUID promoted) {
        Integer position = r.getStatus() == RegistrationStatus.WAITLISTED
                ? (int) registrations.waitlistPosition(r.getEvent().getId(), r.getWaitlistSeq())
                : null;
        Event event = r.getEvent();
        return new RegistrationResponse(
                r.getId(),
                event.getId(),
                event.getCode(),
                event.getTitle(),
                event.getStartsAt(),
                r.getStatus(),
                position,
                r.isViaPromotion(),
                r.getUserId(),
                r.getUserName(),
                r.getUserEmail(),
                r.getDepartment(),
                r.getYearOfStudy(),
                r.getCreatedAt(),
                r.getConfirmedAt(),
                r.getWaitlistedAt(),
                r.getCancelledAt(),
                promoted);
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("Registration %s was not found".formatted(id));
    }
}
