package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.common.messaging.contract.EventEnvelope;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/** Consumers read exactly what {@link OutboxPublisher} writes, and survive fields added by a newer producer. */
class EnvelopeReaderTest {

    final JsonMapper json = JsonMapper.builder().build();
    final EnvelopeReader reader = new EnvelopeReader(json);

    @Test
    void readsTheEnvelopeAndTypedPayload() {
        UUID messageId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        RegistrationConfirmed payload = new RegistrationConfirmed(
                registrationId,
                7L,
                "TECHFEST26",
                "TechFest",
                UUID.randomUUID(),
                "Priya",
                "p@x.test",
                Instant.parse("2026-10-16T10:00:00Z"),
                false,
                Instant.parse("2026-11-01T04:00:00Z"),
                Instant.parse("2026-11-01T12:00:00Z"));

        EventEnvelope<RegistrationConfirmed> envelope =
                reader.read(body(messageId, "registration.confirmed", payload, false), RegistrationConfirmed.class);

        assertThat(envelope.messageId()).isEqualTo(messageId);
        assertThat(envelope.type()).isEqualTo("registration.confirmed");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-16T10:00:01Z"));
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    void ignoresFieldsAProducerAddedLater() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registrationId", UUID.randomUUID());
        payload.put("eventId", 3);
        payload.put("viaPromotion", true);
        payload.put("someFutureField", "ignored");

        EventEnvelope<RegistrationConfirmed> envelope = reader.read(
                body(UUID.randomUUID(), "registration.confirmed", payload, true), RegistrationConfirmed.class);

        assertThat(envelope.payload().eventId()).isEqualTo(3L);
        assertThat(envelope.payload().viaPromotion()).isTrue();
    }

    @Test
    void typeComesFromThePropertyOrTheBody() {
        byte[] bytes = body(UUID.randomUUID(), "event.state-changed", Map.of("eventId", 1), false);
        MessageProperties withType = new MessageProperties();
        withType.setType("event.rescheduled");

        assertThat(reader.type(new Message(bytes, withType))).isEqualTo("event.rescheduled");
        assertThat(reader.type(new Message(bytes, new MessageProperties()))).isEqualTo("event.state-changed");
    }

    @Test
    void missingPayloadIsRejected() {
        byte[] bytes = "{\"messageId\":null,\"type\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(bytes, RegistrationConfirmed.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] body(UUID messageId, String type, Object payload, boolean extraEnvelopeField) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("type", type);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", Instant.parse("2026-10-16T10:00:01Z"));
        envelope.put("aggregateType", "Registration");
        envelope.put("aggregateId", "r-1");
        envelope.put("correlationId", "corr-1");
        envelope.put("payload", payload);
        if (extraEnvelopeField) {
            envelope.put("traceId", "abc");
        }
        return json.writeValueAsBytes(envelope);
    }
}
