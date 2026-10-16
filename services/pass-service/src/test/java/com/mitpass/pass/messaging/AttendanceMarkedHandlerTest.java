package com.mitpass.pass.messaging;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.messaging.contract.AttendanceMarked;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.repository.PassRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code AttendanceMarked} from checkin-service (docs/plans/phase-5.md §4, owner Q2): the pass records its first
 * check-in, and a single-entry pass is marked used. No new pass state.
 */
@PassIntegrationTest
class AttendanceMarkedHandlerTest {

    @Autowired
    PassInboxListener listener;

    @Autowired
    PassRepository passes;

    @Autowired
    TestClock clock;

    TestEvent event;
    UUID registration;
    Pass pass;

    @BeforeEach
    void issue() {
        clock.set(TestClock.START);
        event = PassFixtures.event(Duration.ofHours(1)); // past the unlock time: ACTIVE at once
        registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        pass = passes.findByRegistrationId(registration).orElseThrow();
    }

    @Test
    void firstCheckInIsRecordedOnceAndUsedOnlyForSingleEntry() {
        Instant first = TestClock.START.plusSeconds(60);
        listener.onMessage(message(marked(pass, 0, first, false)));
        listener.onMessage(message(marked(pass, 7, first.plusSeconds(3600), false))); // a later session

        Pass reloaded = passes.findById(pass.getId()).orElseThrow();
        assertThat(reloaded.getCheckedInAt()).isEqualTo(first);
        assertThat(reloaded.getUsedAt()).as("re-entry allowed: not used up").isNull();
        assertThat(reloaded.getStatus()).as("no new state (owner Q2)").isEqualTo(pass.getStatus());
    }

    @Test
    void singleEntryPassIsMarkedUsedAndRedeliveryChangesNothing() {
        Instant first = TestClock.START.plusSeconds(60);
        var message = message(marked(pass, 0, first, true));

        listener.onMessage(message);
        listener.onMessage(message);

        Pass reloaded = passes.findById(pass.getId()).orElseThrow();
        assertThat(reloaded.getCheckedInAt()).isEqualTo(first);
        assertThat(reloaded.getUsedAt()).isEqualTo(first);
    }

    @Test
    void anUnknownPassIsIgnored() {
        Pass stranger = passes.findById(pass.getId()).orElseThrow();
        AttendanceMarked unknown = new AttendanceMarked(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "X26-000001-0",
                UUID.randomUUID(),
                event.id(),
                0,
                stranger.getUserId(),
                1L,
                "MAIN",
                UUID.randomUUID(),
                "QR_LIVE",
                TestClock.START,
                TestClock.START,
                true);

        listener.onMessage(message(unknown)); // must not throw (no retries, no DLQ)

        assertThat(passes.findById(pass.getId()).orElseThrow().getCheckedInAt()).isNull();
    }

    static AttendanceMarked marked(Pass pass, long sessionId, Instant at, boolean singleEntry) {
        return new AttendanceMarked(
                UUID.randomUUID(),
                pass.getId(),
                pass.getPassNumber(),
                pass.getRegistrationId(),
                pass.getEvent().getId(),
                sessionId,
                pass.getUserId(),
                1L,
                "MAIN",
                UUID.randomUUID(),
                "QR_LIVE",
                at,
                at,
                singleEntry);
    }
}
