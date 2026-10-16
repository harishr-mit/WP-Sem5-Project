package com.mitpass.checkin;

import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.EventCatalog.GateInfo;
import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.PassRevoked;
import com.mitpass.common.pass.PassNumber;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/** Events, passes and AMQP messages for checkin-service tests. Ids are unique per JVM: the database is shared. */
public final class CheckinFixtures {

    public static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final AtomicLong IDS = new AtomicLong(50_000);
    private static final AtomicInteger CODES = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CheckinFixtures() {}

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    /** An event of the shared organizer, {@code status} as given, with optional sessions. */
    public static EventInfo event(
            String status,
            Instant startsAt,
            Instant endsAt,
            boolean reentry,
            Integer minSessions,
            SessionInfo... sessions) {
        long id = nextId();
        return new EventInfo(
                id,
                "CHK" + CODES.incrementAndGet(),
                "Check-in test " + id,
                ORGANIZER,
                startsAt,
                endsAt,
                status,
                reentry,
                minSessions,
                List.of(sessions));
    }

    public static GateInfo gate(String code) {
        return new GateInfo(nextId(), code, code + " gate");
    }

    public static SessionInfo session(String title, Instant startsAt, Instant endsAt) {
        return new SessionInfo(nextId(), title, startsAt, endsAt);
    }

    /** The {@code seq}-th pass of an event, as pass-service would describe it. */
    public static PassRecord pass(EventInfo event, int seq, String holder) {
        return new PassRecord(
                UUID.randomUUID(),
                PassNumber.of(event.code(), seq),
                "ACTIVE",
                UUID.randomUUID(),
                event.id(),
                UUID.randomUUID(),
                holder,
                holder.toLowerCase().replace(' ', '.') + "@gmail.com",
                Instant.parse("2026-11-01T00:00:00Z"),
                null,
                null);
    }

    public static PassIssued issued(PassRecord pass, EventInfo event) {
        return new PassIssued(
                pass.id(),
                pass.passNumber(),
                pass.registrationId(),
                event.id(),
                event.code(),
                event.title(),
                pass.userId(),
                pass.holderName(),
                pass.holderEmail(),
                pass.issuedAt(),
                event.startsAt());
    }

    public static PassRevoked revoked(PassRecord pass, Instant at) {
        return new PassRevoked(
                pass.id(),
                pass.passNumber(),
                pass.registrationId(),
                pass.eventId(),
                pass.userId(),
                "ORGANIZER",
                at,
                ORGANIZER.toString());
    }

    public static EventStateChanged stateChanged(EventInfo event, String from, String to, Instant at) {
        return new EventStateChanged(
                event.id(), event.code(), from, to, at, "system", "SCHEDULER", event.startsAt(), event.endsAt());
    }

    /** An AMQP message as the outbox relay sends it. */
    public static Message message(Object payload, UUID messageId) {
        EventType type = payload.getClass().getAnnotation(EventType.class);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("type", type.value());
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", TestClock.START);
        envelope.put("aggregateType", "Test");
        envelope.put("aggregateId", "1");
        envelope.put("correlationId", null);
        envelope.put("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(type.value());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(JSON.writeValueAsBytes(envelope), properties);
    }

    public static Message message(Object payload) {
        return message(payload, UUID.randomUUID());
    }
}
