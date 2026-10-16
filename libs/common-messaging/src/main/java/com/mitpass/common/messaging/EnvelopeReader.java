package com.mitpass.common.messaging;

import com.mitpass.common.messaging.contract.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a message body as an {@link EventEnvelope} with a typed payload. Unknown fields are ignored, because producers
 * may add fields to a contract at any time (D11: fields are only ever added).
 */
public class EnvelopeReader {

    private final JsonMapper json;

    public EnvelopeReader(JsonMapper json) {
        this.json = json.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** The event type: the AMQP {@code type} property, else the envelope's {@code type}. */
    public String type(Message message) {
        String type = message.getMessageProperties().getType();
        return type != null ? type : text(json.readTree(message.getBody()), "type");
    }

    public <T> EventEnvelope<T> read(Message message, Class<T> payloadType) {
        return read(message.getBody(), payloadType);
    }

    public <T> EventEnvelope<T> read(byte[] body, Class<T> payloadType) {
        JsonNode root = json.readTree(body);
        JsonNode payload = root.get("payload");
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("Message has no payload");
        }
        String messageId = text(root, "messageId");
        String occurredAt = text(root, "occurredAt");
        return new EventEnvelope<>(
                messageId == null ? null : UUID.fromString(messageId),
                text(root, "type"),
                root.path("schemaVersion").asInt(1),
                occurredAt == null ? null : Instant.parse(occurredAt),
                text(root, "aggregateType"),
                text(root, "aggregateId"),
                text(root, "correlationId"),
                json.treeToValue(payload, payloadType));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
