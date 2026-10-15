package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.messaging.TestApplication.TestHappened;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The relay against a real PostgreSQL and RabbitMQ: order, properties, confirms, retry after a failure, and two relays
 * racing for the same rows. The relay doesn't start by itself here; each test calls {@code pollOnce()}.
 */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.relay.auto-startup=false",
            "mitpass.messaging.relay.batch-size=10",
            "mitpass.messaging.tap-queue.enabled=true",
            "spring.rabbitmq.publisher-confirm-type=simple",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql"
        })
@Import(OutboxRelayTest.Containers.class)
class OutboxRelayTest {

    static final String TAP = "mitpass.events.tap";

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management-alpine");
        }
    }

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    MessagingProperties properties;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM outbox_message").update();
        admin.initialize(); // declare the exchange and tap queue now, then empty the queue
        admin.purgeQueue(TAP, false);
    }

    @Test
    void publishesInOrderWithIdTypeAndCorrelationId() {
        MDC.put("correlationId", "corr-relay");
        List<UUID> ids = publish(3);
        MDC.clear();

        assertThat(relay.pollOnce()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            Message message = rabbit.receive(TAP, 5_000);
            assertThat(message).as("message %d", i).isNotNull();
            assertThat(message.getMessageProperties().getMessageId())
                    .isEqualTo(ids.get(i).toString());
            assertThat(message.getMessageProperties().getType()).isEqualTo("test.happened");
            assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("test.happened");
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat((String) message.getMessageProperties().getHeader("correlationId"))
                    .isEqualTo("corr-relay");
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(body, "$.payload.note")).isEqualTo("n" + i);
        }
        assertThat(unpublished()).isZero();
        assertThat(relay.pollOnce()).as("nothing left").isZero();
    }

    @Test
    void aFailedPublishIsRecordedAndRetried() {
        publish(2);
        MessagingProperties broken =
                new MessagingProperties("no.such.exchange", properties.relay(), properties.tapQueue());
        OutboxRelay brokenRelay = new OutboxRelay(jdbc, tx, rabbit, broken, Clock.systemUTC());

        assertThat(brokenRelay.pollOnce()).isZero();

        assertThat(unpublished()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT min(attempts) FROM outbox_message")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT min(last_error) FROM outbox_message")
                        .query(String.class)
                        .single())
                .isNotBlank();

        assertThat(relay.pollOnce()).isEqualTo(2);
        assertThat(unpublished()).isZero();
        assertThat(rabbit.receive(TAP, 5_000)).isNotNull();
        assertThat(rabbit.receive(TAP, 5_000)).isNotNull();
    }

    @Test
    void twoRelaysNeverSendTheSameRow() throws Exception {
        List<UUID> ids = publish(40);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                await(start);
                int sent = 0;
                int n;
                while ((n = relay.pollOnce()) > 0) {
                    sent += n;
                }
                return sent;
            }));
        }
        start.countDown();
        int total = 0;
        for (CompletableFuture<Integer> run : runs) {
            total += run.get();
        }

        assertThat(total).isEqualTo(40);
        Set<String> received = new HashSet<>();
        Message message;
        while ((message = rabbit.receive(TAP, 2_000)) != null) {
            assertThat(received.add(message.getMessageProperties().getMessageId()))
                    .as("duplicate")
                    .isTrue();
        }
        assertThat(received).hasSize(ids.size());
    }

    @Test
    void cleanupDeletesOnlyOldPublishedRows() {
        publish(3);
        relay.pollOnce();
        publish(1); // unpublished: never deleted

        assertThat(relay.cleanup(Instant.now())).as("too young").isZero();
        int deleted =
                relay.cleanup(Instant.now().plus(properties.relay().retention()).plus(Duration.ofMinutes(1)));

        assertThat(deleted).isEqualTo(3);
        assertThat(unpublished()).isEqualTo(1);
    }

    private List<UUID> publish(int count) {
        return tx.execute(status -> {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(publisher.publish("Thing", "1", new TestHappened(UUID.randomUUID(), "n" + i)));
            }
            return ids;
        });
    }

    private long unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox_message WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
