package com.mitpass.pass.messaging;

import com.mitpass.common.messaging.InboxQueues;
import com.mitpass.common.messaging.MessagingProperties;
import com.mitpass.common.messaging.contract.AttendanceMarked;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pass-service's inbox (docs/DECISIONS.md D13): {@code pass-service.inbox} bound to the event types it consumes,
 * with the minimal dead-letter queue {@code pass-service.inbox.dlq} (pulled forward from P7; P7 completes it).
 */
@Configuration(proxyBeanMethods = false)
public class PassInbox {

    public static final String QUEUE = "pass-service.inbox";
    public static final String CONSUMER = "pass-service";

    static final String CONFIRMED = type(RegistrationConfirmed.class);
    static final String CANCELLED = type(RegistrationCancelled.class);
    static final String STATE_CHANGED = type(EventStateChanged.class);
    static final String RESCHEDULED = type(EventRescheduled.class);
    static final String ATTENDANCE = type(AttendanceMarked.class);

    @Bean
    Declarables passInboxQueues(MessagingProperties properties) {
        return InboxQueues.inbox(properties, QUEUE, CONFIRMED, CANCELLED, STATE_CHANGED, RESCHEDULED, ATTENDANCE);
    }

    private static String type(Class<?> contract) {
        return contract.getAnnotation(EventType.class).value();
    }
}
