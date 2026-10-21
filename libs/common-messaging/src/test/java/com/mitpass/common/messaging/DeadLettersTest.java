package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Inspect, replay and discard dead-lettered messages; the health component turns WARNING while a DLQ isn't empty. */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.relay.enabled=false",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql"
        })
@Import(DeadLettersTest.Containers.class)
class DeadLettersTest {

    static final String INBOX = "dlq-test.inbox";

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

        @Bean
        Declarables testInbox(MessagingProperties properties) {
            return InboxQueues.inbox(properties, INBOX, "dlq-test.happened");
        }
    }

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    @Qualifier("deadLettersHealthIndicator")
    HealthIndicator deadLettersHealthIndicator;

    @BeforeEach
    void emptyQueues() {
        rabbit.execute(channel -> {
            channel.queuePurge(INBOX);
            channel.queuePurge(InboxQueues.deadLetterQueue(INBOX));
            return null;
        });
    }

    @Test
    void findsItsInboxFromTheDeclarations() {
        assertThat(deadLetters.inboxes()).containsExactly(INBOX);
    }

    @Test
    void listLeavesMessagesInPlace() {
        deadLetter("m-1", "first");
        deadLetter("m-2", "second");

        assertThat(deadLetters.list(50))
                .extracting(DeadLetters.DeadLetter::messageId)
                .containsExactlyInAnyOrder("m-1", "m-2");
        assertThat(deadLetters.list(50)).hasSize(2);
        assertThat(deadLetters.list(50).getFirst().reason()).isEqualTo("rejected");
        assertThat(deadLetters.list(50).getFirst().routingKey()).isEqualTo("dlq-test.happened");
        assertThat(deadLetters.count(INBOX)).isEqualTo(2);
    }

    @Test
    void replayMovesOneMessageBackWithItsIdAndWithoutDeathHeaders() {
        deadLetter("m-1", "first");
        deadLetter("m-2", "second");

        assertThat(deadLetters.replay("m-2")).isTrue();
        assertThat(deadLetters.replay("missing")).isFalse();

        Message back = rabbit.receive(INBOX, 5000);
        assertThat(back).isNotNull();
        assertThat(back.getMessageProperties().getMessageId()).isEqualTo("m-2");
        assertThat(back.getMessageProperties().getHeaders()).doesNotContainKey("x-death");
        assertThat(new String(back.getBody(), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(deadLetters.list(50))
                .extracting(DeadLetters.DeadLetter::messageId)
                .containsExactly("m-1");
    }

    @Test
    void replayAllAndDiscard() {
        deadLetter("m-1", "first");
        deadLetter("m-2", "second");
        deadLetter("m-3", "third");

        assertThat(deadLetters.discard("m-1")).isTrue();
        assertThat(deadLetters.discard("m-1")).isFalse();
        assertThat(deadLetters.replayAll()).isEqualTo(2);
        assertThat(deadLetters.count(INBOX)).isZero();
        assertThat(rabbit.receive(INBOX, 5000)).isNotNull();
        assertThat(rabbit.receive(INBOX, 5000)).isNotNull();
        assertThat(rabbit.receive(INBOX, 500)).isNull();
    }

    @Test
    void healthIsWarningWhileADlqHoldsMessages() {
        assertThat(deadLettersHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
        deadLetter("m-1", "first");
        assertThat(deadLettersHealthIndicator.health().getStatus().getCode()).isEqualTo("WARNING");
    }

    /** Publishes to the inbox and rejects it, as the listener does after its retries. */
    private void deadLetter(String messageId, String body) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setType("dlq-test.happened");
        rabbit.send("mitpass.events", "dlq-test.happened", new Message(body.getBytes(StandardCharsets.UTF_8), props));
        rabbit.execute(channel -> {
            var response = channel.basicGet(INBOX, false);
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            return null;
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (deadLetters.list(50).stream().noneMatch(d -> messageId.equals(d.messageId()))
                && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }
}
