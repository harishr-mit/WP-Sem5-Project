package com.mitpass.common.messaging;

import com.mitpass.common.messaging.contract.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a message handler at most once per {@code (consumer, messageId)} (docs/DECISIONS.md D13). In one transaction:
 * insert into {@code processed_message} with {@code ON CONFLICT DO NOTHING}; if the row was new, run the handler, else
 * skip. A handler exception rolls the row back too, so a retry runs the handler again. Two concurrent deliveries of one
 * message: PostgreSQL makes the second insert wait for the first transaction and then reports the conflict.
 *
 * <p>The message's {@code correlationId} goes into the MDC while the handler runs, so logs and any outbox events the
 * handler publishes carry the original request's id.
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private static final String CLAIM = """
            INSERT INTO processed_message (consumer, message_id, processed_at)
            VALUES (:consumer, :messageId, :now)
            ON CONFLICT DO NOTHING""";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final EnvelopeReader reader;
    private final Clock clock;
    private final Duration retention;
    private volatile Instant nextCleanup = Instant.MIN;

    public IdempotentConsumer(
            JdbcClient jdbc, TransactionTemplate transactions, EnvelopeReader reader, Clock clock, Duration retention) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.reader = reader;
        this.clock = clock;
        this.retention = retention;
    }

    public EnvelopeReader reader() {
        return reader;
    }

    /**
     * Reads the message as an envelope of {@code payloadType} and runs {@code handler} unless this consumer has already
     * processed the message id.
     *
     * @return true if the handler ran, false for a duplicate
     */
    public <T> boolean handle(
            Message message, String consumer, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {
        EventEnvelope<T> envelope = reader.read(message, payloadType);
        UUID messageId = envelope.messageId() != null
                ? envelope.messageId()
                : UUID.fromString(message.getMessageProperties().getMessageId());
        return handle(envelope, messageId, consumer, handler);
    }

    /** As {@link #handle(Message, String, Class, Consumer)}, for an envelope already read. */
    public <T> boolean handle(
            EventEnvelope<T> envelope, UUID messageId, String consumer, Consumer<EventEnvelope<T>> handler) {
        String previousCorrelation = MDC.get(OutboxPublisher.MDC_CORRELATION_ID);
        if (envelope.correlationId() != null) {
            MDC.put(OutboxPublisher.MDC_CORRELATION_ID, envelope.correlationId());
        }
        MDC.put("messageId", messageId.toString());
        try {
            Boolean ran = transactions.execute(status -> {
                int claimed = jdbc.sql(CLAIM)
                        .param("consumer", consumer)
                        .param("messageId", messageId)
                        .param("now", Timestamp.from(clock.instant()))
                        .update();
                if (claimed == 0) {
                    log.info("Skipping duplicate {} message {} for {}", envelope.type(), messageId, consumer);
                    return false;
                }
                handler.accept(envelope);
                return true;
            });
            cleanupIfDue();
            return Boolean.TRUE.equals(ran);
        } finally {
            MDC.remove("messageId");
            if (previousCorrelation != null) {
                MDC.put(OutboxPublisher.MDC_CORRELATION_ID, previousCorrelation);
            } else {
                MDC.remove(OutboxPublisher.MDC_CORRELATION_ID);
            }
        }
    }

    /** Deletes processed-message rows older than the retention; returns how many. */
    public int cleanup(Instant now) {
        return jdbc.sql("DELETE FROM processed_message WHERE processed_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(retention)))
                .update();
    }

    private void cleanupIfDue() {
        Instant now = clock.instant();
        if (now.isAfter(nextCleanup)) {
            nextCleanup = now.plus(Duration.ofHours(1));
            try {
                int deleted = cleanup(now);
                if (deleted > 0) {
                    log.info("Deleted {} processed-message row(s) older than {}", deleted, retention);
                }
            } catch (RuntimeException ex) {
                log.warn("processed_message cleanup failed: {}", ex.getMessage());
            }
        }
    }
}
