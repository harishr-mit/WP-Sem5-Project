package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.common.messaging.TestApplication.TestHappened;
import com.mitpass.common.messaging.contract.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A message is handled at most once per consumer, and a failed attempt can be retried (docs/DECISIONS.md D13). */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.relay.enabled=false",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql,"
                    + "classpath:mitpass/messaging/processed-message-postgresql.sql"
        })
@Import(IdempotentConsumerTest.Containers.class)
class IdempotentConsumerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @Autowired
    IdempotentConsumer consumer;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM processed_message").update();
        jdbc.sql("DELETE FROM outbox_message").update();
    }

    @Test
    void secondDeliveryIsSkipped() {
        Message message = message(UUID.randomUUID(), "corr-7");
        AtomicInteger runs = new AtomicInteger();

        boolean first = consumer.handle(message, "test", TestHappened.class, e -> runs.incrementAndGet());
        boolean second = consumer.handle(message, "test", TestHappened.class, e -> runs.incrementAndGet());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(runs).hasValue(1);
        assertThat(processedRows()).isEqualTo(1);
    }

    @Test
    void consumersAreTrackedSeparately() {
        Message message = message(UUID.randomUUID(), null);

        assertThat(consumer.handle(message, "a", TestHappened.class, e -> {})).isTrue();
        assertThat(consumer.handle(message, "b", TestHappened.class, e -> {})).isTrue();
        assertThat(processedRows()).isEqualTo(2);
    }

    @Test
    void failedHandlerRollsBackSoARetryRunsAgain() {
        Message message = message(UUID.randomUUID(), null);

        assertThatThrownBy(() -> consumer.handle(message, "test", TestHappened.class, e -> {
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");
        assertThat(processedRows()).isZero();

        AtomicInteger runs = new AtomicInteger();
        assertThat(consumer.handle(message, "test", TestHappened.class, e -> runs.incrementAndGet()))
                .isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void handlerSeesTheCorrelationIdAndItsOutboxRowsCarryIt() {
        Message message = message(UUID.randomUUID(), "corr-chain");
        List<String> seen = new ArrayList<>();

        consumer.handle(message, "test", TestHappened.class, envelope -> {
            seen.add(MDC.get("correlationId"));
            publisher.publish("Thing", "t", new TestHappened(UUID.randomUUID(), "follow-up"));
        });

        assertThat(seen).containsExactly("corr-chain");
        assertThat(jdbc.sql("SELECT headers ->> 'correlationId' FROM outbox_message")
                        .query(String.class)
                        .single())
                .isEqualTo("corr-chain");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void concurrentDuplicatesRunTheHandlerOnce() throws Exception {
        Message message = message(UUID.randomUUID(), null);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return consumer.handle(message, "test", TestHappened.class, (EventEnvelope<TestHappened> e) -> {
                        runs.incrementAndGet();
                        sleep(200); // hold the transaction so the others really wait on the row
                    });
                }));
            }
            start.countDown();
            int handled = 0;
            for (Future<Boolean> result : results) {
                handled += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(handled).isEqualTo(1);
            assertThat(runs).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupDeletesOldRows() {
        consumer.handle(message(UUID.randomUUID(), null), "test", TestHappened.class, e -> {});

        assertThat(consumer.cleanup(Instant.now())).isZero();
        assertThat(consumer.cleanup(Instant.now().plusSeconds(31L * 24 * 3600))).isEqualTo(1);
    }

    private int processedRows() {
        return jdbc.sql("SELECT count(*) FROM processed_message")
                .query(Integer.class)
                .single();
    }

    private Message message(UUID messageId, String correlationId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("type", "test.happened");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", Instant.now());
        envelope.put("aggregateType", "Thing");
        envelope.put("aggregateId", "t");
        envelope.put("correlationId", correlationId);
        envelope.put("payload", new TestHappened(UUID.randomUUID(), "hello"));
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType("test.happened");
        return new Message(json.writeValueAsBytes(envelope), properties);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
