package com.mitpass.notification.messaging;

import com.mitpass.common.messaging.IdempotentConsumer;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationWaitlisted;
import com.mitpass.common.messaging.contract.WaitlistPromoted;
import com.mitpass.notification.service.Notifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes notification-service's inbox. Each message runs once per message id through {@link IdempotentConsumer}; an
 * exception (SMTP down, event-service unreachable) rolls back, the container retries 3 times and then dead-letters it.
 */
@Component
public class NotificationInboxListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationInboxListener.class);

    private final IdempotentConsumer consumer;
    private final Notifications notifications;

    public NotificationInboxListener(IdempotentConsumer consumer, Notifications notifications) {
        this.consumer = consumer;
        this.notifications = notifications;
    }

    @RabbitListener(
            id = "notificationInbox",
            queues = NotificationInbox.QUEUE,
            autoStartup = "${mitpass.notifications.listener.auto-startup:true}")
    public void onMessage(Message message) {
        String type = consumer.reader().type(message);
        String me = NotificationInbox.CONSUMER;
        if (NotificationInbox.PASS_ISSUED.equals(type)) {
            consumer.handle(message, me, PassIssued.class, e -> notifications.passIssued(e.messageId(), e.payload()));
        } else if (NotificationInbox.WAITLISTED.equals(type)) {
            consumer.handle(
                    message,
                    me,
                    RegistrationWaitlisted.class,
                    e -> notifications.waitlisted(e.messageId(), e.payload()));
        } else if (NotificationInbox.PROMOTED.equals(type)) {
            consumer.handle(
                    message, me, WaitlistPromoted.class, e -> notifications.promoted(e.messageId(), e.payload()));
        } else if (NotificationInbox.CANCELLED.equals(type)) {
            consumer.handle(
                    message,
                    me,
                    RegistrationCancelled.class,
                    e -> notifications.registrationCancelled(e.messageId(), e.payload()));
        } else if (NotificationInbox.STATE_CHANGED.equals(type)) {
            consumer.handle(
                    message,
                    me,
                    EventStateChanged.class,
                    e -> notifications.eventStateChanged(e.messageId(), e.payload()));
        } else if (NotificationInbox.RESCHEDULED.equals(type)) {
            consumer.handle(
                    message, me, EventRescheduled.class, e -> notifications.rescheduled(e.messageId(), e.payload()));
        } else {
            log.warn("Ignoring message of unexpected type {}", type);
        }
    }
}
