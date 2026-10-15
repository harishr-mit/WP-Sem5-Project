package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The exchange and the tap queue exist as soon as the service starts, before any event is published (exit criterion
 * 1: visible in the RabbitMQ UI). Checked through the management API, because any AMQP call from the test would itself
 * open a connection and trigger the declarations. Found in the live demo: Spring AMQP declares lazily.
 */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.tap-queue.enabled=true",
            "spring.rabbitmq.publisher-confirm-type=simple",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql"
        })
@Import(TopologyOnStartupTest.Containers.class)
class TopologyOnStartupTest {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

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
            return RABBIT;
        }
    }

    @Test
    void exchangeAndTapQueueAreDeclaredAtStartup() throws Exception {
        assertThat(managementStatus("/api/exchanges/%2F/mitpass.events")).isEqualTo(200);
        assertThat(managementStatus("/api/queues/%2F/mitpass.events.tap")).isEqualTo(200);
    }

    private static int managementStatus(String path) throws Exception {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                .header(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
