package com.mitpass.pass.service;

import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.PassRevoked;
import com.mitpass.pass.domain.Pass;
import org.springframework.stereotype.Component;

/** The domain events a pass change produces, written to the outbox in the caller's transaction (D11). */
@Component
public class PassEvents {

    static final String AGGREGATE = "Pass";

    private final OutboxPublisher outbox;

    public PassEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    void issued(Pass pass) {
        outbox.publish(
                AGGREGATE,
                pass.getId().toString(),
                new PassIssued(
                        pass.getId(),
                        pass.getPassNumber(),
                        pass.getRegistrationId(),
                        pass.getEvent().getId(),
                        pass.getEvent().getCode(),
                        pass.getEvent().getTitle(),
                        pass.getUserId(),
                        pass.getHolderName(),
                        pass.getHolderEmail(),
                        pass.getIssuedAt(),
                        pass.getEvent().getStartsAt()));
    }

    void revoked(Pass pass) {
        outbox.publish(
                AGGREGATE,
                pass.getId().toString(),
                new PassRevoked(
                        pass.getId(),
                        pass.getPassNumber(),
                        pass.getRegistrationId(),
                        pass.getEvent().getId(),
                        pass.getUserId(),
                        pass.getRevokeReason().name(),
                        pass.getRevokedAt(),
                        pass.getRevokedBy()));
    }
}
