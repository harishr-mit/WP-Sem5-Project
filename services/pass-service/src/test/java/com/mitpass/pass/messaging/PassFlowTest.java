package com.mitpass.pass.messaging;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.messaging.InboxQueues;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassTestConfig;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.TestcontainersConfiguration;
import com.mitpass.security.test.TestSecurityConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The real path on a real broker (exit criteria 2 and 1): a {@code RegistrationConfirmed} published to
 * {@code mitpass.events} becomes a pass, with the request's correlation id carried into {@code PassIssued}; a message
 * that keeps failing lands in {@code pass-service.inbox.dlq} after three attempts (minimal DLQ, D13).
 */
@SpringBootTest(properties = {"mitpass.passes.listener.auto-startup=true", "mitpass.messaging.relay.enabled=true"})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, PassTestConfig.class, PassFlowTest.Broker.class})
class PassFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Broker {
        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management-alpine");
        }
    }

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Test
    void confirmedRegistrationOnTheBrokerBecomesAPassAndPassIssuedIsPublished() {
        clock.set(TestClock.START);
        TestEvent event = PassFixtures.event(Duration.ofDays(3));
        UUID registration = UUID.randomUUID();
        Message message = message(confirmed(event, registration, UUID.randomUUID()), UUID.randomUUID(), "corr-flow");

        rabbit.send("mitpass.events", "registration.confirmed", message);

        assertThat(waitFor(() -> count("SELECT count(*) FROM pass WHERE registration_id = '" + registration + "'")))
                .isEqualTo(1);
        // The relay publishes PassIssued; published_at is set after the broker's confirm.
        assertThat(waitFor(() -> count("SELECT count(*) FROM outbox_message WHERE event_type = 'pass.issued'"
                        + " AND published_at IS NOT NULL AND headers ->> 'correlationId' = 'corr-flow'")))
                .isEqualTo(1);
    }

    @Test
    void aMessageThatKeepsFailingIsDeadLettered() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setType("registration.confirmed");
        Message poison = new Message(
                "{\"messageId\":null,\"type\":\"registration.confirmed\"}".getBytes(StandardCharsets.UTF_8),
                properties);

        rabbit.send("mitpass.events", "registration.confirmed", poison);

        Message dead = rabbit.receive(InboxQueues.deadLetterQueue(PassInbox.QUEUE), 15_000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(properties.getMessageId());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    /** Polls for up to 10 s until the value is positive. */
    private static int waitFor(Supplier<Integer> value) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int current = value.get();
        while (current == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            current = value.get();
        }
        return current;
    }
}
