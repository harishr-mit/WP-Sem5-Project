package com.mitpass.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.messaging.OutboxRelay;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.service.EventService;
import com.mitpass.event.service.RegistrationService;
import com.mitpass.event.web.dto.RegistrationResponse;
import com.mitpass.security.test.TestSecurityConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
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
 * Registration facts reach RabbitMQ through the outbox, in order and with the correlation id (docs/plans/phase-3.md
 * exit criterion 4). Its own context: a RabbitMQ container and the relay switched on (polled by hand here).
 */
@SpringBootTest(properties = {"mitpass.messaging.relay.enabled=true", "mitpass.messaging.relay.auto-startup=false"})
@ActiveProfiles("test")
@Import({
    TestcontainersConfiguration.class,
    TestSecurityConfig.class,
    EventIntegrationTest.AuthStub.class,
    MessagingFlowTest.Rabbit.class
})
class MessagingFlowTest {

    static final String QUEUE = "test.event-service.all";

    @TestConfiguration(proxyBeanMethods = false)
    static class Rabbit {
        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management-alpine");
        }
    }

    @Autowired
    RegistrationService registrations;

    @Autowired
    EventService events;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    OutboxRelay relay;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    TopicExchange mitpassEventsExchange;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void listenToEverything() {
        admin.declareQueue(new Queue(QUEUE)); // durable: RabbitMQ 4 refuses transient non-exclusive queues
        admin.declareBinding(
                BindingBuilder.bind(new Queue(QUEUE)).to(mitpassEventsExchange).with("#"));
        relay.pollOnce(); // anything left from an earlier test
        admin.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void cancellingWithAWaiterPublishesCancelledPromotedConfirmedInOrder() {
        long event = openEvent("FLOW1", 1);
        RegistrationResponse first = registrations.register(student(), event);
        RegistrationResponse second = registrations.register(student(), event);

        MDC.put("correlationId", "demo-corr-42");
        registrations.cancel(first.id(), "student", null);
        MDC.remove("correlationId");
        relay.pollOnce();

        List<Message> messages = drain();
        assertThat(messages)
                .extracting(m -> m.getMessageProperties().getType())
                .containsExactly(
                        "event.state-changed", // PUBLISHED
                        "event.state-changed", // REGISTRATION_OPEN
                        "registration.confirmed", // first student
                        "registration.waitlisted", // second student (P7)
                        "registration.cancelled",
                        "registration.waitlist-promoted",
                        "registration.confirmed"); // second student, via promotion

        String waitlisted = new String(messages.get(3).getBody(), StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(waitlisted, "$.payload.position")).isEqualTo(1);
        assertThat((String) JsonPath.read(waitlisted, "$.payload.userEmail")).isEqualTo(second.userEmail());
        String cancelled = new String(messages.get(4).getBody(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(cancelled, "$.payload.userEmail")).isEqualTo(first.userEmail());
        assertThat((String) JsonPath.read(cancelled, "$.payload.userName")).isEqualTo("Student");

        Message promotedConfirm = messages.get(6);
        String body = new String(promotedConfirm.getBody(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(body, "$.payload.registrationId"))
                .isEqualTo(second.id().toString());
        assertThat((Boolean) JsonPath.read(body, "$.payload.viaPromotion")).isTrue();
        assertThat((String) JsonPath.read(body, "$.aggregateType")).isEqualTo("Registration");
        assertThat(messages.subList(4, 7))
                .allSatisfy(m -> assertThat((String) m.getMessageProperties().getHeader("correlationId"))
                        .isEqualTo("demo-corr-42"));
        String state = new String(messages.get(1).getBody(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(state, "$.payload.toStatus")).isEqualTo("REGISTRATION_OPEN");
    }

    @Test
    void waitlistingPublishesOneRowAndARolledBackRequestLeavesNone() {
        long event = openEvent("FLOW2", 1);
        registrations.register(student(), event);
        long before = outboxRows();

        var waiting = student();
        registrations.register(waiting, event); // waitlisted
        assertThat(outboxRows()).isEqualTo(before + 1);

        long afterWaitlist = outboxRows();
        // A request that fails (already registered) leaves no row.
        assertThatThrownBy(() -> registrations.register(waiting, event)).isInstanceOf(ProblemException.class);
        assertThat(outboxRows()).isEqualTo(afterWaitlist);
    }

    private long openEvent(String code, int capacity) {
        long id = events.create(TestData.ORGANIZER_USER, TestData.createRequest(code, capacity, List.of(), null, null))
                .id();
        lifecycle.transition(id, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
        return id;
    }

    private List<Message> drain() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(QUEUE, 2_000)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private long outboxRows() {
        return jdbc.sql("SELECT count(*) FROM outbox_message").query(Long.class).single();
    }

    private static CurrentUser student() {
        UUID id = UUID.randomUUID();
        return new CurrentUser(id, id + "@student.test", "Student", Set.of("STUDENT"), "CSE", 2, null, "token");
    }
}
