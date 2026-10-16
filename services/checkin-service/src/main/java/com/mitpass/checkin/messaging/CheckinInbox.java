package com.mitpass.checkin.messaging;

import com.mitpass.common.messaging.InboxQueues;
import com.mitpass.common.messaging.MessagingProperties;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.PassRevoked;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * checkin-service's inbox (D13): {@code checkin-service.inbox} bound to the facts its copies follow (D14), with its
 * dead-letter queue {@code checkin-service.inbox.dlq}. A message that never arrives is still caught by the reconciler.
 */
@Configuration(proxyBeanMethods = false)
public class CheckinInbox {

    public static final String QUEUE = "checkin-service.inbox";
    public static final String CONSUMER = "checkin-service";

    static final String ISSUED = type(PassIssued.class);
    static final String REVOKED = type(PassRevoked.class);
    static final String STATE_CHANGED = type(EventStateChanged.class);

    @Bean
    Declarables checkinInboxQueues(MessagingProperties properties) {
        return InboxQueues.inbox(properties, QUEUE, ISSUED, REVOKED, STATE_CHANGED);
    }

    private static String type(Class<?> contract) {
        return contract.getAnnotation(EventType.class).value();
    }
}
