package com.mitpass.common.messaging;

import com.mitpass.common.messaging.contract.EventType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a domain event to {@code outbox_message} in the caller's transaction (docs/DECISIONS.md D11), so the event
 * exists if and only if the business change commits. {@link OutboxRelay} sends it to RabbitMQ later.
 *
 * <p>{@code MANDATORY}: calling it outside a transaction is a bug (the event could commit without the change, or the
 * other way round), so it fails loudly.
 */
public class OutboxPublisher {

    /** MDC keys filled by common-web's correlation filter and common-security's user filter. */
    static final String MDC_CORRELATION_ID = "correlationId";

    static final String MDC_USER_ID = "userId";

    private static final String INSERT = """
            INSERT INTO outbox_message (id, aggregate_type, aggregate_id, event_type, payload, headers, occurred_at)
            VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), CAST(:headers AS jsonb),
                    :occurredAt)""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxPublisher(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param aggregateType for example {@code Registration}
     * @param aggregateId the aggregate's id as text
     * @param event a contract record annotated with {@link EventType}
     * @return the message id (also the AMQP {@code messageId})
     */
    /**
     * The current trace as a W3C {@code traceparent}, read from the log MDC that Micrometer Tracing fills, so the relay
     * can hand it to the consumers and one request stays one trace across RabbitMQ (D17). Null without tracing.
     * shortcut: always flagged sampled (sampling is 100 % here); read the real flag if sampling is ever lowered.
     */
    static String traceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null) {
            return null;
        }
        return "00-" + "0".repeat(Math.max(0, 32 - traceId.length())) + traceId + "-" + spanId + "-01";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, String aggregateId, Object event) {
        EventType type = event.getClass().getAnnotation(EventType.class);
        if (type == null) {
            throw new IllegalArgumentException(event.getClass().getName() + " is not annotated with @EventType");
        }
        UUID id = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        String correlationId = MDC.get(MDC_CORRELATION_ID);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", id);
        envelope.put("type", type.value());
        envelope.put("schemaVersion", type.schemaVersion());
        envelope.put("occurredAt", occurredAt);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("correlationId", correlationId);
        envelope.put("payload", event);

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("correlationId", correlationId);
        headers.put("userId", MDC.get(MDC_USER_ID));
        headers.put("traceparent", traceparent());

        jdbc.sql(INSERT)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", type.value())
                .param("payload", json.writeValueAsString(envelope))
                .param("headers", json.writeValueAsString(headers))
                .param("occurredAt", java.sql.Timestamp.from(occurredAt))
                .update();
        return id;
    }
}
