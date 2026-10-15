package com.mitpass.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.web.dto.RegistrationResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The phase's headline demo (docs/plans/phase-3.md exit criterion 3): 500 students register at once for 100 seats.
 * The real service confirms exactly 100 and waitlists 400 in order; a naive "count, compare, insert" overbooks. Run it
 * alone with {@code ./mvnw -pl services/event-service verify -Dtest=SeatClaimConcurrencyTest}.
 */
@EventIntegrationTest
class SeatClaimConcurrencyTest {

    static final int STUDENTS = 500;
    static final int SEATS = 100;
    static final int THREADS = 32;
    static final AtomicInteger CODES = new AtomicInteger();

    @Autowired
    RegistrationService registrations;

    @Autowired
    EventService events;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void fiveHundredStudentsForOneHundredSeats() throws Exception {
        long event = openEvent(SEATS);

        List<RegistrationResponse> results = runTogether(STUDENTS, i -> registrations.register(student(), event));

        long confirmed = results.stream()
                .filter(r -> r.status() == RegistrationStatus.CONFIRMED)
                .count();
        long waitlisted = results.stream()
                .filter(r -> r.status() == RegistrationStatus.WAITLISTED)
                .count();
        int seatsTaken = seatsTaken(event);
        List<Long> positions = jdbc.sql("""
                        SELECT count(*) OVER (ORDER BY waitlist_seq) FROM registration
                        WHERE event_id = :e AND status = 'WAITLISTED' ORDER BY waitlist_seq""").param("e", event).query(Long.class).list();
        long outboxConfirmed = jdbc.sql("""
                        SELECT count(*) FROM outbox_message
                        WHERE event_type = 'registration.confirmed' AND (payload -> 'payload' ->> 'eventId')::bigint = :e""").param("e", event).query(Long.class).single();

        report("real service (conditional UPDATE + row lock)", confirmed, waitlisted, seatsTaken);
        assertThat(confirmed).isEqualTo(SEATS);
        assertThat(waitlisted).isEqualTo(STUDENTS - SEATS);
        assertThat(seatsTaken).isEqualTo(SEATS);
        assertThat(countByStatus(event, "CONFIRMED")).isEqualTo(SEATS);
        assertThat(positions)
                .as("waitlist positions 1..400, no gaps or repeats")
                .hasSize(STUDENTS - SEATS);
        for (int i = 0; i < positions.size(); i++) {
            assertThat(positions.get(i)).isEqualTo(i + 1L);
        }
        assertThat(outboxConfirmed).as("one RegistrationConfirmed per seat").isEqualTo(SEATS);
    }

    @Test
    void theNaiveVersionOverbooks() throws Exception {
        long event = openEvent(SEATS);
        NaiveRegistrar naive = new NaiveRegistrar(jdbc, new TransactionTemplate(transactionManager));

        runTogether(STUDENTS, i -> {
            naive.register(event, SEATS);
            return null;
        });

        long confirmed = countByStatus(event, "CONFIRMED");
        report("naive: count, compare, insert", confirmed, STUDENTS - confirmed, -1);
        assertThat(confirmed).as("the race lets more than %d through", SEATS).isGreaterThan(SEATS);
    }

    @Test
    void cancellationsAndRegistrationsRacingKeepTheInvariant() throws Exception {
        int seats = 50;
        long event = openEvent(seats);
        List<RegistrationResponse> holders = new ArrayList<>();
        for (int i = 0; i < seats * 2; i++) {
            RegistrationResponse r = registrations.register(student(), event);
            if (r.status() == RegistrationStatus.CONFIRMED) {
                holders.add(r);
            }
        }

        runTogether(
                seats,
                i -> i % 2 == 0
                        ? registrations.cancel(holders.get(i).id(), "student", "race")
                        : registrations.register(student(), event));

        int seatsTaken = seatsTaken(event);
        long confirmed = countByStatus(event, "CONFIRMED");
        long waitlisted = countByStatus(event, "WAITLISTED");
        report("cancel/register race (50 seats)", confirmed, waitlisted, seatsTaken);
        assertThat(seatsTaken).as("counter matches the rows").isEqualTo(confirmed);
        assertThat(seatsTaken).as("full while anyone waits").isEqualTo(seats);
        assertThat(waitlisted).isEqualTo(seats - seats / 2 + seats / 2); // 50 waiting - 25 promoted + 25 new
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    interface Task<T> {
        T run(int index) throws Exception;
    }

    /** Starts every task at the same moment (a latch) on a bounded pool; fails the test on any exception. */
    private static <T> List<T> runTogether(int count, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> results = Collections.synchronizedList(new ArrayList<>());
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long openEvent(int capacity) {
        long id = events.create(
                        TestData.ORGANIZER_USER,
                        TestData.createRequest("RACE" + CODES.incrementAndGet(), capacity, List.of(), null, null))
                .id();
        lifecycle.transition(id, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
        return id;
    }

    private int seatsTaken(long event) {
        return jdbc.sql("SELECT seats_taken FROM event WHERE id = :e")
                .param("e", event)
                .query(Integer.class)
                .single();
    }

    private long countByStatus(long event, String status) {
        return jdbc.sql("SELECT count(*) FROM registration WHERE event_id = :e AND status = :s")
                .param("e", event)
                .param("s", status)
                .query(Long.class)
                .single();
    }

    private static void report(String variant, long confirmed, long waitlisted, int seatsTaken) {
        System.out.printf(
                "%n| %-46s | confirmed %4d | waitlisted %4d | seats_taken %4s |%n",
                variant, confirmed, waitlisted, seatsTaken < 0 ? "n/a" : seatsTaken);
    }

    private static CurrentUser student() {
        UUID id = UUID.randomUUID();
        return new CurrentUser(id, id + "@student.test", "Student", Set.of("STUDENT"), "CSE", 2, null, "token");
    }

    /**
     * What not to do, kept in test code only so it can never be used by mistake: read the number of confirmed seats,
     * compare it with the capacity, then insert. Between the read and the insert other transactions do the same, so
     * they all see "a seat is left". The short pause just makes the window wide enough to show every time.
     */
    static final class NaiveRegistrar {

        private final JdbcClient jdbc;
        private final TransactionTemplate tx;

        NaiveRegistrar(JdbcClient jdbc, TransactionTemplate tx) {
            this.jdbc = jdbc;
            this.tx = tx;
        }

        void register(long eventId, int capacity) {
            tx.executeWithoutResult(status -> {
                long confirmed = jdbc.sql(
                                "SELECT count(*) FROM registration WHERE event_id = :e AND status = 'CONFIRMED'")
                        .param("e", eventId)
                        .query(Long.class)
                        .single();
                pause();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.sql("""
                                INSERT INTO registration (id, event_id, user_id, user_name, user_email, status,
                                    confirmed_at, created_at, created_by, updated_at, updated_by)
                                VALUES (:id, :e, :u, 'Naive', 'naive@test', :status, :now, :now, 'naive', :now, 'naive')""")
                        .param("id", UUID.randomUUID())
                        .param("e", eventId)
                        .param("u", UUID.randomUUID())
                        .param("status", confirmed < capacity ? "CONFIRMED" : "CANCELLED")
                        .param("now", now)
                        .update();
            });
        }

        private static void pause() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
