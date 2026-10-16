package com.mitpass.pass.messaging;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A redelivered {@code RegistrationConfirmed} never issues a second pass (PROJECT.md §P4, exit criterion 6), whether
 * it arrives again later or several copies arrive at once.
 */
@PassIntegrationTest
class ConsumerIdempotencyTest {

    @Autowired
    PassInboxListener listener;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    TestEvent event;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        event = PassFixtures.event(Duration.ofDays(10));
    }

    @Test
    void sameMessageTwiceIssuesOnePass() {
        UUID registration = UUID.randomUUID();
        Message message = message(confirmed(event, registration, UUID.randomUUID()));

        listener.onMessage(message);
        listener.onMessage(message);

        assertThat(passes(registration)).isEqualTo(1);
        assertThat(issuedEvents(registration)).isEqualTo(1);
        assertThat(processed(message)).isEqualTo(1);
    }

    @Test
    void concurrentCopiesIssueOnePass() throws Exception {
        UUID registration = UUID.randomUUID();
        Message message = message(confirmed(event, registration, UUID.randomUUID()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> copies = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                copies.add(pool.submit(() -> {
                    start.await();
                    listener.onMessage(message);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> copy : copies) {
                copy.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(passes(registration)).isEqualTo(1);
        assertThat(issuedEvents(registration)).isEqualTo(1);
    }

    @Test
    void aDifferentMessageForTheSameRegistrationStillIssuesOnePass() {
        // Not a redelivery (new message id), e.g. a replayed outbox row: the business rule catches it.
        UUID registration = UUID.randomUUID();

        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));

        assertThat(passes(registration)).isEqualTo(1);
        assertThat(issuedEvents(registration)).isEqualTo(1);
    }

    private int passes(UUID registration) {
        return jdbc.sql("SELECT count(*) FROM pass WHERE registration_id = :id")
                .param("id", registration)
                .query(Integer.class)
                .single();
    }

    private int issuedEvents(UUID registration) {
        return jdbc.sql("SELECT count(*) FROM outbox_message WHERE event_type = 'pass.issued'"
                        + " AND payload -> 'payload' ->> 'registrationId' = :id")
                .param("id", registration.toString())
                .query(Integer.class)
                .single();
    }

    private int processed(Message message) {
        return jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id")
                .param("id", UUID.fromString(message.getMessageProperties().getMessageId()))
                .query(Integer.class)
                .single();
    }
}
