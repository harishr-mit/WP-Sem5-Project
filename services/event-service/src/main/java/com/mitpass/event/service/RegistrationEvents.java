package com.mitpass.event.service;

import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import com.mitpass.common.messaging.contract.WaitlistPromoted;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Registration;
import com.mitpass.event.domain.RegistrationStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The domain events a registration change produces (docs/DECISIONS.md D11), written to the outbox in the caller's
 * transaction. One place, so every path (register, cancel, promotion, capacity increase) publishes the same facts.
 * Waitlisting publishes nothing in P3.
 */
@Component
public class RegistrationEvents {

    static final String AGGREGATE = "Registration";

    private final OutboxPublisher outbox;

    public RegistrationEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /** A seat was taken straight away (not via the waitlist). */
    void confirmed(Registration registration) {
        outbox.publish(AGGREGATE, registration.getId().toString(), confirmedEvent(registration));
    }

    /** A waitlisted student got a seat: WaitlistPromoted (for the email) plus RegistrationConfirmed (for the pass). */
    void promoted(Registration registration, UUID freedBy) {
        Event event = registration.getEvent();
        outbox.publish(
                AGGREGATE,
                registration.getId().toString(),
                new WaitlistPromoted(
                        registration.getId(),
                        event.getId(),
                        event.getCode(),
                        registration.getUserId(),
                        registration.getUserEmail(),
                        registration.getConfirmedAt(),
                        freedBy));
        outbox.publish(AGGREGATE, registration.getId().toString(), confirmedEvent(registration));
    }

    void cancelled(Registration registration, RegistrationStatus previousStatus) {
        Event event = registration.getEvent();
        outbox.publish(
                AGGREGATE,
                registration.getId().toString(),
                new RegistrationCancelled(
                        registration.getId(),
                        event.getId(),
                        event.getCode(),
                        registration.getUserId(),
                        previousStatus.name(),
                        registration.getCancelledAt(),
                        registration.getCancelledBy()));
    }

    private static RegistrationConfirmed confirmedEvent(Registration registration) {
        Event event = registration.getEvent();
        return new RegistrationConfirmed(
                registration.getId(),
                event.getId(),
                event.getCode(),
                event.getTitle(),
                registration.getUserId(),
                registration.getUserName(),
                registration.getUserEmail(),
                registration.getConfirmedAt(),
                registration.isViaPromotion(),
                event.getStartsAt(),
                event.getEndsAt());
    }
}
