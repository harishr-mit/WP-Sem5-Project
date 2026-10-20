package com.mitpass.notification.service;

import static com.mitpass.notification.Fixtures.mailTo;
import static com.mitpass.notification.Fixtures.recipient;
import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.mitpass.notification.FakeEventDirectory;
import com.mitpass.notification.Fixtures;
import com.mitpass.notification.NotificationIntegrationTest;
import com.mitpass.notification.TestClock;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Recipient;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Reminders 24 h and 1 h before, once per registration, also when two runs overlap (PROJECT.md §P7). */
@NotificationIntegrationTest
class ReminderJobTest {

    @Autowired
    ReminderJob job;

    @Autowired
    FakeEventDirectory events;

    @Autowired
    TestClock clock;

    @Autowired
    GreenMail greenMail;

    @Autowired
    LockProvider lockProvider;

    @AfterEach
    void resetClock() {
        clock.set(TestClock.START);
    }

    @Test
    void eachReminderGoesOutOnceInItsWindow() {
        // an hour of slack before "now", so events left by other tests (10 days out) never fall in a window
        Instant now = TestClock.START.plus(Duration.ofDays(30));
        clock.set(now);
        EventInfo event = Fixtures.event(now.plus(Duration.ofHours(24)).minus(Duration.ofMinutes(1)));
        events.put(event);
        Recipient a = recipient("CONFIRMED");
        Recipient b = recipient("CONFIRMED");
        events.registrations(event.id(), "CONFIRMED", List.of(a, b));

        job.runOnce();
        job.runOnce(); // still in the window: nothing new
        assertThat(subjects(a)).containsExactly(event.title() + " starts in 24 hours");

        clock.set(now.plus(Duration.ofHours(23))); // 1 h minus a minute before the start
        job.runOnce();
        assertThat(subjects(a))
                .containsExactly(event.title() + " starts in 24 hours", event.title() + " starts in 1 hour");
        assertThat(subjects(b)).hasSize(2);
    }

    @Test
    void notBeforeTheWindowAndNotForACancelledEvent() {
        Instant now = TestClock.START.plus(Duration.ofDays(60));
        clock.set(now);
        EventInfo later = Fixtures.event(now.plus(Duration.ofHours(30)));
        EventInfo base = Fixtures.event(now.plus(Duration.ofHours(24)).minus(Duration.ofMinutes(2)));
        EventInfo cancelled = new EventInfo(
                base.id(), base.code(), base.title(), base.startsAt(), base.endsAt(), "CANCELLED", 3L, base.venue());
        events.put(later);
        events.put(cancelled);
        Recipient a = recipient("CONFIRMED");
        Recipient b = recipient("CONFIRMED");
        events.registrations(later.id(), "CONFIRMED", List.of(a));
        events.registrations(cancelled.id(), "CONFIRMED", List.of(b));

        job.runOnce();

        assertThat(mailTo(greenMail, a.userEmail())).isEmpty();
        assertThat(mailTo(greenMail, b.userEmail())).isEmpty();
    }

    @Test
    void twoOverlappingRunsSendEachReminderOnce() throws Exception {
        Instant now = TestClock.START.plus(Duration.ofDays(90));
        clock.set(now);
        EventInfo event = Fixtures.event(now.plus(Duration.ofHours(1)).minus(Duration.ofMinutes(1)));
        events.put(event);
        List<Recipient> recipients =
                List.of(recipient("CONFIRMED"), recipient("CONFIRMED"), recipient("CONFIRMED"), recipient("CONFIRMED"));
        events.registrations(event.id(), "CONFIRMED", recipients);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = job::runOnce;
            int sent = pool.invokeAll(List.of(run, run)).stream()
                    .mapToInt(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sum();
            assertThat(sent).isEqualTo(4);
        } finally {
            pool.shutdown();
        }
        for (Recipient r : recipients) {
            assertThat(mailTo(greenMail, r.userEmail())).hasSize(1);
        }
    }

    @Test
    void shedLockLetsOneInstanceHoldTheJob() {
        LockConfiguration config =
                new LockConfiguration(Instant.now(), "reminder-test-lock", Duration.ofMinutes(1), Duration.ZERO);
        Optional<SimpleLock> first = lockProvider.lock(config);
        Optional<SimpleLock> second = lockProvider.lock(config);
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        first.get().unlock();
        Optional<SimpleLock> third = lockProvider.lock(config);
        assertThat(third).isPresent();
        third.get().unlock();
    }

    private List<String> subjects(Recipient recipient) {
        return mailTo(greenMail, recipient.userEmail()).stream()
                .map(m -> {
                    try {
                        return ((MimeMessage) m).getSubject();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
