package com.mitpass.pass.service;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.TransitionTrigger;
import com.mitpass.pass.messaging.PassInboxListener;
import com.mitpass.pass.repository.PassChangeRepository;
import com.mitpass.pass.repository.PassRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Unlock (ISSUED → ACTIVE) and expiry happen once, at the right time, and skip passes changed meanwhile. */
@PassIntegrationTest
class PassLifecycleSchedulerTest {

    @Autowired
    PassLifecycleScheduler scheduler;

    @Autowired
    PassLifecycleService lifecycle;

    @Autowired
    PassInboxListener listener;

    @Autowired
    PassRepository passes;

    @Autowired
    PassChangeRepository history;

    @Autowired
    TestClock clock;

    TestEvent event;
    UUID registration;

    @BeforeEach
    void issue() {
        clock.set(TestClock.START);
        event = PassFixtures.event(Duration.ofDays(2));
        registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
    }

    @Test
    void theLiveCodeUnlocksTwoAndAHalfHoursBeforeTheStartOnce() {
        scheduler.runOnce(event.startsAt().minus(Duration.ofMinutes(151)));
        assertThat(pass().getStatus()).isEqualTo(PassStatus.ISSUED);

        scheduler.runOnce(event.startsAt().minus(Duration.ofMinutes(150)));
        assertThat(pass().getStatus()).isEqualTo(PassStatus.ACTIVE);

        scheduler.runOnce(event.startsAt().minus(Duration.ofMinutes(149)));
        assertThat(history.findByPass_IdOrderByChangedAtAscIdAsc(pass().getId()))
                .extracting(change -> change.getToStatus() + "/" + change.getTrigger())
                .containsExactly("ISSUED/SYSTEM", "ACTIVE/" + TransitionTrigger.SCHEDULER);
    }

    @Test
    void passesExpireTwoHoursAfterTheEnd() {
        scheduler.runOnce(event.startsAt());
        scheduler.runOnce(event.endsAt().plus(Duration.ofMinutes(119)));
        assertThat(pass().getStatus()).isEqualTo(PassStatus.ACTIVE);

        scheduler.runOnce(event.endsAt().plus(Duration.ofHours(2)));
        assertThat(pass().getStatus()).isEqualTo(PassStatus.EXPIRED);
    }

    @Test
    void anIssuedPassWhoseEventIsOverExpiresWithoutUnlocking() {
        scheduler.runOnce(event.endsAt().plus(Duration.ofHours(3)));

        assertThat(pass().getStatus()).isEqualTo(PassStatus.EXPIRED);
        assertThat(history.findByPass_IdOrderByChangedAtAscIdAsc(pass().getId()))
                .extracting(change -> change.getToStatus().name())
                .containsExactly("ISSUED", "EXPIRED");
    }

    @Test
    void aRevokedPassIsLeftAlone() {
        lifecycle.revokeByStaff(pass().getId(), "organizer", false, null);

        scheduler.runOnce(event.startsAt());
        scheduler.runOnce(event.endsAt().plus(Duration.ofHours(3)));

        assertThat(pass().getStatus()).isEqualTo(PassStatus.REVOKED);
    }

    private Pass pass() {
        return passes.findByRegistrationId(registration).orElseThrow();
    }
}
