package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * An inbox declared with {@link InboxQueues} receives its routing keys only, and a message rejected without requeue
 * lands in {@code <inbox>.dlq} (minimal DLQ, D13; completed in P7).
 */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.relay.enabled=false",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql"
        })
@Import(DeadLetterTopologyTest.Containers.class)
class DeadLetterTopologyTest {

    static final String INBOX = "test-service.inbox";

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
            return InboxQueues.inbox(properties, INBOX, "test.happened");
        }
    }

    @Autowired
    RabbitTemplate rabbit;

    @Test
    void boundKeysArriveAndRejectedMessagesAreDeadLettered() {
        rabbit.send("mitpass.events", "test.other", message("not for me"));
        rabbit.send("mitpass.events", "test.happened", message("poison"));

        GetResponse rejected = rabbit.execute(channel -> {
            GetResponse response = channel.basicGet(INBOX, false);
            if (response != null) {
                channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            }
            return response;
        });
        assertThat(rejected).isNotNull();
        assertThat(new String(rejected.getBody(), StandardCharsets.UTF_8)).isEqualTo("poison");

        Message deadLettered = rabbit.receive(InboxQueues.deadLetterQueue(INBOX), 5000);
        assertThat(deadLettered).isNotNull();
        assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).isEqualTo("poison");
        assertThat(rabbit.receive(INBOX)).isNull();
    }

    private static Message message(String text) {
        return new Message(text.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
