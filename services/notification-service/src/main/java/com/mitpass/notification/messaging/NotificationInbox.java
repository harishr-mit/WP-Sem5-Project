package com.mitpass.notification.messaging;

import com.mitpass.common.messaging.InboxQueues;
import com.mitpass.common.messaging.MessagingProperties;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationWaitlisted;
import com.mitpass.common.messaging.contract.WaitlistPromoted;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * notification-service's inbox (D13): {@code notification-service.inbox} bound to every fact a student hears about,
 * with its dead-letter queue {@code notification-service.inbox.dlq} (an SMTP failure ends there after the retries).
 */
@Configuration(proxyBeanMethods = false)
public class NotificationInbox {

    public static final String QUEUE = "notification-service.inbox";
    public static final String CONSUMER = "notification-service";

    static final String PASS_ISSUED = type(PassIssued.class);
    static final String WAITLISTED = type(RegistrationWaitlisted.class);
    static final String PROMOTED = type(WaitlistPromoted.class);
    static final String CANCELLED = type(RegistrationCancelled.class);
    static final String STATE_CHANGED = type(EventStateChanged.class);
    static final String RESCHEDULED = type(EventRescheduled.class);

    @Bean
    Declarables notificationInboxQueues(MessagingProperties properties) {
        return InboxQueues.inbox(
                properties, QUEUE, PASS_ISSUED, WAITLISTED, PROMOTED, CANCELLED, STATE_CHANGED, RESCHEDULED);
    }

    private static String type(Class<?> contract) {
        return contract.getAnnotation(EventType.class).value();
    }
}
