package com.mitpass.event.service;

import com.mitpass.event.domain.Registration;
import com.mitpass.event.domain.RegistrationChange;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.RegistrationChangeRepository;
import com.mitpass.event.repository.RegistrationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves students from the waitlist into seats (docs/DECISIONS.md D10). Always runs inside the caller's transaction,
 * which already holds the event row lock (a cancellation) or the row lock of its own event UPDATE (a capacity
 * increase), so two promotions on one event never interleave. The waitlist head is also locked {@code FOR UPDATE}.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WaitlistService {

    private final RegistrationRepository registrations;
    private final RegistrationChangeRepository history;
    private final EventRepository events;
    private final RegistrationEvents outbox;
    private final Clock clock;

    public WaitlistService(
            RegistrationRepository registrations,
            RegistrationChangeRepository history,
            EventRepository events,
            RegistrationEvents outbox,
            Clock clock) {
        this.registrations = registrations;
        this.history = history;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Confirms the first waitlisted student, if there is one.
     *
     * @param seatAlreadyHeld true when a cancelled CONFIRMED registration hands its seat over (the counter stays the
     *     same); false to take a new seat (capacity increase), which fails if none is left
     * @param freedBy the cancelled registration whose seat is passed on, or null
     */
    public Optional<Registration> promoteNext(long eventId, boolean seatAlreadyHeld, UUID freedBy) {
        Optional<UUID> head = registrations.lockFirstWaitlisted(eventId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        if (!seatAlreadyHeld && events.claimSeatForPromotion(eventId) == 0) {
            return Optional.empty();
        }
        Registration promoted = registrations.findWithEventById(head.get()).orElseThrow();
        Instant now = clock.instant();
        promoted.confirm(now, true);
        String reason = freedBy != null ? "Seat freed by registration " + freedBy : "Capacity increased";
        history.save(new RegistrationChange(
                promoted,
                RegistrationStatus.WAITLISTED,
                RegistrationStatus.CONFIRMED,
                now,
                "system",
                TransitionTrigger.SYSTEM,
                reason));
        outbox.promoted(promoted, freedBy);
        return Optional.of(promoted);
    }

    /** Fills up to {@code seats} newly added seats from the waitlist; returns how many students moved up. */
    public int promoteUpTo(long eventId, int seats) {
        int promoted = 0;
        while (promoted < seats && promoteNext(eventId, false, null).isPresent()) {
            promoted++;
        }
        return promoted;
    }
}
