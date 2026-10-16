package com.mitpass.pass.messaging;

import com.mitpass.common.messaging.IdempotentConsumer;
import com.mitpass.common.messaging.contract.AttendanceMarked;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import com.mitpass.pass.service.PassLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes pass-service's inbox (docs/plans/phase-4.md §8). Each message runs once per message id through
 * {@link IdempotentConsumer}: the processed-message row and the pass changes commit together. An exception rolls both
 * back; the container retries (3 attempts) and then dead-letters the message.
 */
@Component
public class PassInboxListener {

    private static final Logger log = LoggerFactory.getLogger(PassInboxListener.class);

    private final IdempotentConsumer consumer;
    private final PassLifecycleService lifecycle;

    public PassInboxListener(IdempotentConsumer consumer, PassLifecycleService lifecycle) {
        this.consumer = consumer;
        this.lifecycle = lifecycle;
    }

    @RabbitListener(
            id = "passInbox",
            queues = PassInbox.QUEUE,
            autoStartup = "${mitpass.passes.listener.auto-startup:true}")
    public void onMessage(Message message) {
        String type = consumer.reader().type(message);
        if (PassInbox.CONFIRMED.equals(type)) {
            consumer.handle(
                    message,
                    PassInbox.CONSUMER,
                    RegistrationConfirmed.class,
                    envelope -> lifecycle.issue(envelope.payload(), envelope.occurredAt()));
        } else if (PassInbox.CANCELLED.equals(type)) {
            consumer.handle(
                    message,
                    PassInbox.CONSUMER,
                    RegistrationCancelled.class,
                    envelope -> lifecycle.registrationCancelled(envelope.payload()));
        } else if (PassInbox.STATE_CHANGED.equals(type)) {
            consumer.handle(
                    message,
                    PassInbox.CONSUMER,
                    EventStateChanged.class,
                    envelope -> lifecycle.eventStateChanged(envelope.payload()));
        } else if (PassInbox.RESCHEDULED.equals(type)) {
            consumer.handle(
                    message,
                    PassInbox.CONSUMER,
                    EventRescheduled.class,
                    envelope -> lifecycle.eventRescheduled(envelope.payload()));
        } else if (PassInbox.ATTENDANCE.equals(type)) {
            consumer.handle(
                    message,
                    PassInbox.CONSUMER,
                    AttendanceMarked.class,
                    envelope -> lifecycle.attendanceMarked(envelope.payload()));
        } else {
            log.warn("Ignoring message of unexpected type {}", type);
        }
    }
}
