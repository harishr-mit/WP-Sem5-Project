package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.PassRevoked;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Applies the owners' facts to the copies (D14). Runs inside the consumer's transaction ({@code IdempotentConsumer}),
 * so a redelivered message is skipped and a failed one leaves nothing behind.
 */
@Service
public class CopyUpdates {

    /** Published when an event goes LIVE, so the reconciler syncs its passes right away (after commit). */
    public record EventWentLive(long eventId) {}

    private final PassCopyStore passes;
    private final EventRefStore events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public CopyUpdates(PassCopyStore passes, EventRefStore events, ApplicationEventPublisher publisher, Clock clock) {
        this.passes = passes;
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void passIssued(PassIssued issued) {
        passes.upsert(
                new PassRef(
                        issued.passId(),
                        issued.passNumber(),
                        issued.eventId(),
                        issued.registrationId(),
                        issued.userId(),
                        issued.holderName(),
                        issued.holderEmail(),
                        PassRef.VALID,
                        issued.issuedAt(),
                        null,
                        null),
                clock.instant());
    }

    public void passRevoked(PassRevoked revoked) {
        passes.markRevoked(
                revoked.passId(),
                revoked.eventId(),
                revoked.passNumber(),
                revoked.revokedAt(),
                revoked.reason(),
                clock.instant());
    }

    public void eventStateChanged(EventStateChanged changed) {
        boolean applied = events.apply(
                changed.eventId(), changed.eventCode(), changed.toStatus(), changed.changedAt(), clock.instant());
        if (applied && "LIVE".equals(changed.toStatus())) {
            publisher.publishEvent(new EventWentLive(changed.eventId()));
        }
    }
}
