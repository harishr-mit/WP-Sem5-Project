package com.mitpass.checkin.messaging;

import static com.mitpass.checkin.CheckinFixtures.event;
import static com.mitpass.checkin.CheckinFixtures.issued;
import static com.mitpass.checkin.CheckinFixtures.message;
import static com.mitpass.checkin.CheckinFixtures.pass;
import static com.mitpass.checkin.CheckinFixtures.revoked;
import static com.mitpass.checkin.CheckinFixtures.stateChanged;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The copies follow the owners' messages (docs/plans/phase-5.md §8, D14): in any order, once per message id, and a
 * revocation is never undone by a late or repeated issue.
 */
@CheckinIntegrationTest
class CheckinConsumerTest {

    static final Instant T0 = TestClock.START;

    @Autowired
    CheckinInboxListener listener;

    @Autowired
    PassCopyStore passes;

    @Autowired
    EventRefStore events;

    EventInfo event;
    PassRecord pass;

    @BeforeEach
    void setUp() {
        event = event("REGISTRATION_CLOSED", T0.plusSeconds(3600), T0.plusSeconds(7200), false, null);
        pass = pass(event, 1, "Priya Raman");
    }

    @Test
    void passIssuedFillsTheCopyAndARedeliveryChangesNothing() {
        Message message = message(issued(pass, event));

        listener.onMessage(message);
        listener.onMessage(message);

        PassRef copy = passes.find(pass.id()).orElseThrow();
        assertThat(copy.passNumber()).isEqualTo(pass.passNumber());
        assertThat(copy.holderName()).isEqualTo("Priya Raman");
        assertThat(copy.eventId()).isEqualTo(event.id());
        assertThat(copy.status()).isEqualTo(PassRef.VALID);
        assertThat(passes.findByNumber(pass.passNumber())).isPresent();
    }

    @Test
    void revokedAfterIssuedIsRevoked() {
        listener.onMessage(message(issued(pass, event)));
        listener.onMessage(message(revoked(pass, T0.plusSeconds(5))));

        PassRef copy = passes.find(pass.id()).orElseThrow();
        assertThat(copy.revoked()).isTrue();
        assertThat(copy.revokedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(copy.holderName()).isEqualTo("Priya Raman");
    }

    @Test
    void revokedBeforeIssuedStaysRevokedAndGetsItsDetailsLater() {
        listener.onMessage(message(revoked(pass, T0.plusSeconds(5))));
        assertThat(passes.find(pass.id()).orElseThrow().revoked()).isTrue();

        listener.onMessage(message(issued(pass, event)));

        PassRef copy = passes.find(pass.id()).orElseThrow();
        assertThat(copy.revoked()).as("REVOKED is sticky").isTrue();
        assertThat(copy.holderName()).isEqualTo("Priya Raman");
    }

    @Test
    void eventStatusFollowsTheNewestChangeOnly() {
        listener.onMessage(message(stateChanged(event, "REGISTRATION_CLOSED", "LIVE", T0.plusSeconds(60))));
        assertThat(events.status(event.id())).contains("LIVE");

        // An older change delivered late (a retry) must not undo the newer one.
        listener.onMessage(message(stateChanged(event, "REGISTRATION_OPEN", "REGISTRATION_CLOSED", T0)));
        assertThat(events.status(event.id())).contains("LIVE");

        listener.onMessage(message(stateChanged(event, "LIVE", "COMPLETED", T0.plusSeconds(120))));
        assertThat(events.status(event.id())).contains("COMPLETED");
    }

    @Test
    void anUnknownTypeIsIgnored() {
        var other = new com.mitpass.common.messaging.contract.UserRegistered(
                UUID.randomUUID(), "x@gmail.com", "X", null, null, T0);
        listener.onMessage(message(other)); // must not throw
    }
}
