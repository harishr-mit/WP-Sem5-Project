package com.mitpass.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.messaging.TestApplication.NotAContract;
import com.mitpass.common.messaging.TestApplication.TestHappened;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The outbox row exists exactly when the surrounding transaction commits (docs/DECISIONS.md D11). */
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
            "mitpass.messaging.relay.enabled=false",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:mitpass/messaging/outbox-postgresql.sql"
        })
@Import(OutboxPublisherTest.Containers.class)
class OutboxPublisherTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationContext context;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM outbox_message").update();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesTheEnvelopeInTheCallersTransaction() {
        MDC.put("correlationId", "corr-1");
        MDC.put("userId", "user-1");
        UUID thing = UUID.randomUUID();

        UUID id = tx.execute(status -> publisher.publish("Thing", thing.toString(), new TestHappened(thing, "hi")));

        Map<String, Object> row = jdbc.sql("SELECT event_type, aggregate_type, aggregate_id, payload::text AS payload, "
                        + "headers::text AS headers, published_at, attempts FROM outbox_message WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        assertThat(row.get("event_type")).isEqualTo("test.happened");
        assertThat(row.get("aggregate_type")).isEqualTo("Thing");
        assertThat(row.get("aggregate_id")).isEqualTo(thing.toString());
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(0);

        String payload = (String) row.get("payload");
        assertThat((String) JsonPath.read(payload, "$.messageId")).isEqualTo(id.toString());
        assertThat((String) JsonPath.read(payload, "$.type")).isEqualTo("test.happened");
        assertThat((Integer) JsonPath.read(payload, "$.schemaVersion")).isEqualTo(1);
        assertThat((String) JsonPath.read(payload, "$.correlationId")).isEqualTo("corr-1");
        assertThat((String) JsonPath.read(payload, "$.occurredAt")).endsWith("Z");
        assertThat((String) JsonPath.read(payload, "$.payload.thingId")).isEqualTo(thing.toString());
        assertThat((String) JsonPath.read(payload, "$.payload.note")).isEqualTo("hi");
        assertThat((String) JsonPath.read((String) row.get("headers"), "$.userId"))
                .isEqualTo("user-1");
    }

    @Test
    void nothingIsWrittenWhenTheTransactionRollsBack() {
        tx.executeWithoutResult(status -> {
            publisher.publish("Thing", "1", new TestHappened(UUID.randomUUID(), "lost"));
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
    }

    @Test
    void publishingOutsideATransactionFails() {
        assertThatThrownBy(() -> publisher.publish("Thing", "1", new TestHappened(UUID.randomUUID(), "x")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void anUnannotatedEventIsRejected() {
        assertThatThrownBy(() -> tx.execute(status -> publisher.publish("Thing", "1", new NotAContract("x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@EventType");
    }

    @Test
    void rowsOfOneTransactionKeepTheirOrder() {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 5; i++) {
                publisher.publish("Thing", "1", new TestHappened(UUID.randomUUID(), "n" + i));
            }
        });

        List<String> notes = jdbc.sql("SELECT payload -> 'payload' ->> 'note' FROM outbox_message ORDER BY seq")
                .query(String.class)
                .list();
        assertThat(notes).containsExactly("n0", "n1", "n2", "n3", "n4");
    }

    @Test
    void relayIsNotCreatedWhenDisabled() {
        assertThat(context.getBeansOfType(OutboxRelay.class)).isEmpty();
        assertThat(context.getBean("mitpassEventsExchange")).isNotNull();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM outbox_message").query(Long.class).single();
    }
}
