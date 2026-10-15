package com.mitpass.common.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends unpublished outbox rows to RabbitMQ (docs/DECISIONS.md D11). One poll = one transaction: lock a batch with
 * {@code FOR UPDATE SKIP LOCKED} (several instances never take the same rows), publish it, wait for the broker's
 * confirms, then set {@code published_at}. If anything fails, the rows get {@code attempts + 1} and {@code last_error}
 * and are retried on the next poll. Delivery is therefore at least once; consumers deduplicate on {@code messageId}.
 *
 * <p>It runs on its own single thread, so the library doesn't switch on {@code @EnableScheduling} in every service, and
 * it stops with the application context.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT_BATCH = """
            SELECT id, event_type, payload::text AS payload, headers ->> 'correlationId' AS correlation_id, occurred_at
            FROM outbox_message
            WHERE published_at IS NULL
            ORDER BY seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final MessagingProperties properties;
    private final Clock clock;
    private final boolean confirms;

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile Instant nextCleanup = Instant.MIN;

    public OutboxRelay(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            RabbitTemplate rabbit,
            MessagingProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.properties = properties;
        this.clock = clock;
        this.confirms = rabbit.getConnectionFactory() instanceof CachingConnectionFactory caching
                && caching.isSimplePublisherConfirms();
        if (!confirms) {
            log.warn("Publisher confirms are off (spring.rabbitmq.publisher-confirm-type != simple): "
                    + "outbox rows are marked published without the broker's confirmation");
        }
    }

    record Row(UUID id, String eventType, String payload, String correlationId, Instant occurredAt) {}

    /** Publishes one batch; returns how many rows were published (0 when there was nothing to do or it failed). */
    public int pollOnce() {
        Integer published = transactions.execute(status -> {
            List<Row> rows = jdbc.sql(SELECT_BATCH)
                    .param("limit", properties.relay().batchSize())
                    .query((rs, n) -> new Row(
                            rs.getObject("id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            rs.getString("correlation_id"),
                            rs.getTimestamp("occurred_at").toInstant()))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            List<UUID> ids = rows.stream().map(Row::id).toList();
            try {
                send(rows);
            } catch (RuntimeException ex) {
                String error = describe(ex);
                log.warn("Publishing {} outbox message(s) failed, will retry: {}", rows.size(), error);
                jdbc.sql("UPDATE outbox_message SET attempts = attempts + 1, last_error = :error WHERE id IN (:ids)")
                        .param("error", error)
                        .param("ids", ids)
                        .update();
                return 0;
            }
            jdbc.sql("UPDATE outbox_message SET published_at = :now, attempts = attempts + 1 WHERE id IN (:ids)")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("ids", ids)
                    .update();
            log.info("Published {} outbox message(s) to {}", rows.size(), properties.exchange());
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    /** Deletes published rows older than the retention; returns how many. */
    public int cleanup(Instant now) {
        Instant cutoff = now.minus(properties.relay().retention());
        return jdbc.sql("DELETE FROM outbox_message WHERE published_at IS NOT NULL AND published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private void send(List<Row> rows) {
        rabbit.invoke(operations -> {
            for (Row row : rows) {
                operations.send(properties.exchange(), row.eventType(), toMessage(row));
            }
            if (confirms) {
                operations.waitForConfirmsOrDie(
                        properties.relay().confirmTimeout().toMillis());
            }
            return null;
        });
    }

    private static Message toMessage(Row row) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(row.id().toString());
        props.setType(row.eventType());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setTimestamp(Date.from(row.occurredAt()));
        if (row.correlationId() != null) {
            props.setHeader("correlationId", row.correlationId());
        }
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
    }

    private static String describe(RuntimeException ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = ex.getClass().getSimpleName() + ": " + root.getMessage();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private void loop() {
        try {
            // Drain: keep going while full batches come back.
            while (running && pollOnce() == properties.relay().batchSize()) {
                // next batch
            }
            Instant now = clock.instant();
            if (now.isAfter(nextCleanup)) {
                int deleted = cleanup(now);
                if (deleted > 0) {
                    log.info(
                            "Deleted {} published outbox message(s) older than {}",
                            deleted,
                            properties.relay().retention());
                }
                nextCleanup = now.plusSeconds(3600);
            }
        } catch (RuntimeException ex) {
            // Never let an exception kill the scheduled task; the database or broker may be back next time.
            log.warn("Outbox relay poll failed: {}", describe(ex));
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.relay().pollInterval().toMillis();
        running = true;
        executor.execute(this::declareTopology);
        executor.scheduleWithFixedDelay(this::loop, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Outbox relay started (every {} ms, exchange {})", interval, properties.exchange());
    }

    /**
     * Opens the connection once at startup. Spring AMQP declares exchanges, queues and bindings (RabbitAdmin) only
     * when the first connection opens, so without this the exchange and the tap queue would appear only with the first
     * event. A broker that is down is not fatal: the declarations then happen on the first successful publish.
     */
    private void declareTopology() {
        try {
            rabbit.getConnectionFactory().createConnection().close();
        } catch (RuntimeException ex) {
            log.warn("RabbitMQ not reachable at startup ({}); will retry with the first message", describe(ex));
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.relay().autoStartup();
    }
}
