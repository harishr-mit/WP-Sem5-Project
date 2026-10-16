package com.mitpass.pass.service;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.FakeEventCatalog;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.client.EventCatalogClient.EventInfo;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.RevokeReason;
import com.mitpass.pass.messaging.PassInboxListener;
import com.mitpass.pass.repository.EventRefRepository;
import com.mitpass.pass.repository.PassRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RL7 (owner, 2026-10-16: "take care of it too"): pass-service's copy of event status and times is reconciled with
 * event-service on its own, so a lost {@code EventStateChanged(CANCELLED)} still revokes the passes (the saga step) and
 * a lost {@code EventRescheduled} still moves unlock and expiry times. Nothing here arrives by message.
 */
@PassIntegrationTest
class EventCopyReconcilerTest {

    @Autowired
    EventCopyReconciler reconciler;

    @Autowired
    PassInboxListener listener;

    @Autowired
    FakeEventCatalog catalog;

    @Autowired
    PassRepository passes;

    @Autowired
    EventRefRepository events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    TestEvent event;
    UUID registration;

    @BeforeEach
    void issue() {
        clock.set(TestClock.START);
        event = PassFixtures.event(Duration.ofDays(3));
        catalog.put(event.info(true, null));
        registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
    }

    @Test
    void aCancellationWhoseMessageWasLostStillRevokesThePasses() {
        catalog.put(owner(event, "CANCELLED", event.startsAt(), event.endsAt()));

        reconciler.runOnce();

        Pass pass = passes.findByRegistrationId(registration).orElseThrow();
        assertThat(pass.getStatus()).isEqualTo(PassStatus.REVOKED);
        assertThat(pass.getRevokeReason()).isEqualTo(RevokeReason.EVENT_CANCELLED);
        assertThat(revokedMessages()).isEqualTo(1);
        assertThat(events.findById(event.id()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void aLostRescheduleMovesTheEventTimes() {
        Instant later = event.startsAt().plus(Duration.ofDays(1));
        catalog.put(owner(event, "REGISTRATION_OPEN", later, later.plus(Duration.ofHours(6))));

        reconciler.runOnce();

        assertThat(events.findById(event.id()).orElseThrow().getStartsAt()).isEqualTo(later);
        assertThat(passes.findByRegistrationId(registration).orElseThrow().getStatus())
                .isEqualTo(PassStatus.ISSUED);
    }

    @Test
    void anUnchangedEventIsLeftAloneAndASecondRunChangesNothing() {
        catalog.put(owner(event, "CANCELLED", event.startsAt(), event.endsAt()));
        reconciler.runOnce();
        int history = historyRows();

        clock.advance(Duration.ofMinutes(5));
        reconciler.runOnce();

        assertThat(historyRows()).isEqualTo(history);
        assertThat(revokedMessages()).isEqualTo(1);
    }

    @Test
    void anUnreachableOrUnknownEventIsSkippedWithoutFailing() {
        catalog.down = true;
        try {
            reconciler.runOnce(); // must not throw
        } finally {
            catalog.down = false;
        }
        assertThat(passes.findByRegistrationId(registration).orElseThrow().getStatus())
                .isEqualTo(PassStatus.ISSUED);
    }

    private static EventInfo owner(TestEvent event, String status, Instant startsAt, Instant endsAt) {
        EventInfo base = event.info(true, null);
        return new EventInfo(
                base.id(),
                base.code(),
                base.title(),
                base.organizerId(),
                startsAt,
                endsAt,
                status,
                base.staticQrAllowed(),
                base.passColor(),
                base.venue());
    }

    private int revokedMessages() {
        return jdbc.sql("SELECT count(*) FROM outbox_message WHERE event_type = 'pass.revoked'"
                        + " AND payload -> 'payload' ->> 'registrationId' = :id")
                .param("id", registration.toString())
                .query(Integer.class)
                .single();
    }

    private int historyRows() {
        return jdbc.sql("SELECT count(*) FROM pass_history h JOIN pass p ON p.id = h.pass_id"
                        + " WHERE p.registration_id = :id")
                .param("id", registration)
                .query(Integer.class)
                .single();
    }
}
