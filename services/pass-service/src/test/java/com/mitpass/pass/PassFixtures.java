package com.mitpass.pass;

import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import com.mitpass.pass.client.EventCatalogClient.EventInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds test events and the messages event-service would send, in the exact envelope format of
 * {@code OutboxPublisher}. Event ids start high so they never clash between test classes sharing the database.
 */
public final class PassFixtures {

    public static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final AtomicLong EVENT_IDS = new AtomicLong(10_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PassFixtures() {}

    /** An event in the catalogue; {@code startsIn} is from the test clock's START. */
    public record TestEvent(long id, String code, String title, Instant startsAt, Instant endsAt) {

        public EventInfo info(boolean staticQr, String color) {
            return new EventInfo(
                    id,
                    code,
                    title,
                    ORGANIZER,
                    startsAt,
                    endsAt,
                    "REGISTRATION_OPEN",
                    staticQr,
                    color,
                    new EventInfo.Venue("Main Auditorium", "Admin Block", "https://maps.example/main"));
        }
    }

    public static TestEvent event(Duration startsIn) {
        long id = EVENT_IDS.incrementAndGet();
        Instant start = TestClock.START.plus(startsIn);
        return new TestEvent(id, "EVT" + id, "Test Event " + id, start, start.plus(Duration.ofHours(4)));
    }

    public static RegistrationConfirmed confirmed(TestEvent event, UUID registrationId, UUID userId) {
        return new RegistrationConfirmed(
                registrationId,
                event.id(),
                event.code(),
                event.title(),
                userId,
                "Priya Raman",
                "priya@student.test",
                TestClock.START,
                false,
                event.startsAt(),
                event.endsAt());
    }

    public static RegistrationCancelled cancelled(TestEvent event, UUID registrationId, UUID userId, String previous) {
        return new RegistrationCancelled(
                registrationId,
                event.id(),
                event.code(),
                userId,
                previous,
                TestClock.START,
                userId.toString(),
                "Priya Raman",
                "priya@student.test");
    }

    public static EventStateChanged stateChanged(TestEvent event, String from, String to, Instant at) {
        return new EventStateChanged(
                event.id(),
                event.code(),
                from,
                to,
                at,
                ORGANIZER.toString(),
                "MANUAL",
                event.startsAt(),
                event.endsAt());
    }

    /** An AMQP message as the outbox relay sends it. */
    public static Message message(Object payload, UUID messageId, String correlationId) {
        EventType type = payload.getClass().getAnnotation(EventType.class);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("type", type.value());
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", TestClock.START);
        envelope.put("aggregateType", "Test");
        envelope.put("aggregateId", "1");
        envelope.put("correlationId", correlationId);
        envelope.put("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(type.value());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (correlationId != null) {
            properties.setHeader("correlationId", correlationId);
        }
        return new Message(JSON.writeValueAsBytes(envelope), properties);
    }

    public static Message message(Object payload) {
        return message(payload, UUID.randomUUID(), null);
    }
}
