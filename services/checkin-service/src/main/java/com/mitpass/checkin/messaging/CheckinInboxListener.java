package com.mitpass.checkin.messaging;

import com.mitpass.checkin.service.CopyUpdates;
import com.mitpass.common.messaging.IdempotentConsumer;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.PassRevoked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes checkin-service's inbox. Each message runs once per message id through {@link IdempotentConsumer}; an
 * exception rolls back, the container retries (3 attempts) and then dead-letters it.
 */
@Component
public class CheckinInboxListener {

    private static final Logger log = LoggerFactory.getLogger(CheckinInboxListener.class);

    private final IdempotentConsumer consumer;
    private final CopyUpdates copies;

    public CheckinInboxListener(IdempotentConsumer consumer, CopyUpdates copies) {
        this.consumer = consumer;
        this.copies = copies;
    }

    @RabbitListener(
            id = "checkinInbox",
            queues = CheckinInbox.QUEUE,
            autoStartup = "${mitpass.checkin.listener.auto-startup:true}")
    public void onMessage(Message message) {
        String type = consumer.reader().type(message);
        if (CheckinInbox.ISSUED.equals(type)) {
            consumer.handle(message, CheckinInbox.CONSUMER, PassIssued.class, e -> copies.passIssued(e.payload()));
        } else if (CheckinInbox.REVOKED.equals(type)) {
            consumer.handle(message, CheckinInbox.CONSUMER, PassRevoked.class, e -> copies.passRevoked(e.payload()));
        } else if (CheckinInbox.STATE_CHANGED.equals(type)) {
            consumer.handle(
                    message,
                    CheckinInbox.CONSUMER,
                    EventStateChanged.class,
                    e -> copies.eventStateChanged(e.payload()));
        } else {
            log.warn("Ignoring message of unexpected type {}", type);
        }
    }
}
