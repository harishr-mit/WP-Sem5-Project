package com.mitpass.pass.service;

import static com.mitpass.pass.PassFixtures.cancelled;
import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static com.mitpass.pass.PassFixtures.stateChanged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.pass.PassNumber;
import com.mitpass.common.web.error.IllegalTransitionException;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.RevokeReason;
import com.mitpass.pass.messaging.PassInboxListener;
import com.mitpass.pass.repository.EventRefRepository;
import com.mitpass.pass.repository.PassChangeRepository;
import com.mitpass.pass.repository.PassRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every message pass-service consumes, through the real listener and idempotent consumer (docs/plans/phase-4.md §8):
 * issuance, revocation on cancel, the cancellation saga, expiry, activation, reschedules and reordering.
 */
@PassIntegrationTest
class PassLifecycleServiceTest {

    @Autowired
    PassInboxListener listener;

    @Autowired
    PassLifecycleService lifecycle;

    @Autowired
    PassRepository passes;

    @Autowired
    PassChangeRepository history;

    @Autowired
    EventRefRepository eventRefs;

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
    void confirmedRegistrationGetsAnIssuedPassWithHistoryAndPassIssued() {
        UUID registration = UUID.randomUUID();
        UUID student = UUID.randomUUID();

        listener.onMessage(message(confirmed(event, registration, student), UUID.randomUUID(), "corr-issue"));

        Pass pass = pass(registration);
        assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
        assertThat(pass.getUserId()).isEqualTo(student);
        assertThat(pass.getPassNumber()).startsWith(event.code() + "-000001-");
        assertThat(PassNumber.isValid(pass.getPassNumber())).isTrue();
        assertThat(history.findByPass_IdOrderByChangedAtAscIdAsc(pass.getId()))
                .extracting(change -> change.getToStatus() + "/" + change.getTrigger())
                .containsExactly("ISSUED/SYSTEM");
        String payload = outboxPayload("pass.issued", pass.getId());
        assertThat((String) JsonPath.read(payload, "$.payload.passNumber")).isEqualTo(pass.getPassNumber());
        assertThat((String) JsonPath.read(payload, "$.correlationId")).isEqualTo("corr-issue");
        assertThat(eventRefs.findById(event.id()).orElseThrow().getTitle()).isEqualTo(event.title());
    }

    @Test
    void passNumbersCountUpPerEvent() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, first, UUID.randomUUID())));
        listener.onMessage(message(confirmed(event, second, UUID.randomUUID())));

        assertThat(pass(first).getPassNumber()).contains("-000001-");
        assertThat(pass(second).getPassNumber()).contains("-000002-");
    }

    @Test
    void passIssuedAfterTheUnlockTimeIsActiveAtOnce() {
        TestEvent soon = PassFixtures.event(Duration.ofHours(2)); // unlocks 2 h 30 min before start: already open
        UUID registration = UUID.randomUUID();

        listener.onMessage(message(confirmed(soon, registration, UUID.randomUUID())));

        Pass pass = pass(registration);
        assertThat(pass.getStatus()).isEqualTo(PassStatus.ACTIVE);
        assertThat(history.findByPass_IdOrderByChangedAtAscIdAsc(pass.getId()))
                .extracting(change -> change.getToStatus().name())
                .containsExactly("ISSUED", "ACTIVE");
    }

    @Test
    void cancellingAConfirmedRegistrationRevokesItsPass() {
        UUID registration = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, student)));

        listener.onMessage(message(cancelled(event, registration, student, "CONFIRMED")));

        Pass pass = pass(registration);
        assertThat(pass.getStatus()).isEqualTo(PassStatus.REVOKED);
        assertThat(pass.getRevokeReason()).isEqualTo(RevokeReason.REGISTRATION_CANCELLED);
        assertThat(revocations(pass.getId())).isEqualTo(1);
        assertThat((String) JsonPath.read(outboxPayload("pass.revoked", pass.getId()), "$.payload.reason"))
                .isEqualTo("REGISTRATION_CANCELLED");
    }

    @Test
    void cancellingAWaitlistedRegistrationDoesNothing() {
        UUID registration = UUID.randomUUID();

        listener.onMessage(message(cancelled(event, registration, UUID.randomUUID(), "WAITLISTED")));
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));

        assertThat(passes.findByRegistrationId(registration)).isPresent(); // no tombstone was left
    }

    @Test
    void cancelArrivingBeforeConfirmLeavesATombstoneAndNoPass() {
        UUID registration = UUID.randomUUID();

        listener.onMessage(message(cancelled(event, registration, UUID.randomUUID(), "CONFIRMED")));
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));

        assertThat(passes.findByRegistrationId(registration)).isEmpty();
    }

    @Test
    void cancellingTheEventRevokesEveryValidPassOfItAndNothingElse() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID alreadyRevoked = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, a, UUID.randomUUID())));
        listener.onMessage(message(confirmed(event, b, UUID.randomUUID())));
        listener.onMessage(message(confirmed(event, alreadyRevoked, UUID.randomUUID())));
        listener.onMessage(message(cancelled(event, alreadyRevoked, UUID.randomUUID(), "CONFIRMED")));
        TestEvent other = PassFixtures.event(Duration.ofDays(10));
        UUID elsewhere = UUID.randomUUID();
        listener.onMessage(message(confirmed(other, elsewhere, UUID.randomUUID())));

        listener.onMessage(message(stateChanged(event, "REGISTRATION_OPEN", "CANCELLED", TestClock.START)));

        for (UUID registration : List.of(a, b)) {
            Pass pass = pass(registration);
            assertThat(pass.getStatus()).isEqualTo(PassStatus.REVOKED);
            assertThat(pass.getRevokeReason()).isEqualTo(RevokeReason.EVENT_CANCELLED);
            assertThat(history.findByPass_IdOrderByChangedAtAscIdAsc(pass.getId())
                            .getLast()
                            .getTrigger()
                            .name())
                    .isEqualTo("SYSTEM");
        }
        assertThat(pass(alreadyRevoked).getRevokeReason()).isEqualTo(RevokeReason.REGISTRATION_CANCELLED);
        assertThat(pass(elsewhere).getStatus()).isEqualTo(PassStatus.ISSUED);
        assertThat(outboxCount("pass.revoked", event)).isEqualTo(3);

        // A late confirmation for the cancelled event gets no pass.
        UUID late = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, late, UUID.randomUUID())));
        assertThat(passes.findByRegistrationId(late)).isEmpty();
    }

    // ---- RL10 (D17): an admin brings an event back; REVOKED and EXPIRED stay final, new passes are issued -----

    @Test
    void reinstatingACancelledEventReissuesPassesExceptForRegistrationsCancelledMeanwhile() {
        UUID kept = UUID.randomUUID();
        UUID leftMeanwhile = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, kept, UUID.randomUUID())));
        listener.onMessage(message(confirmed(event, leftMeanwhile, UUID.randomUUID())));
        String oldNumber = pass(kept).getPassNumber();
        listener.onMessage(message(stateChanged(event, "REGISTRATION_OPEN", "CANCELLED", TestClock.START)));
        // the pass is already revoked, but the cancellation must still be remembered
        listener.onMessage(message(cancelled(event, leftMeanwhile, UUID.randomUUID(), "CONFIRMED")));

        listener.onMessage(
                message(stateChanged(event, "CANCELLED", "REGISTRATION_OPEN", TestClock.START.plusSeconds(60))));

        Pass fresh = pass(kept);
        assertThat(fresh.getStatus()).isEqualTo(PassStatus.ISSUED);
        assertThat(fresh.getPassNumber()).isNotEqualTo(oldNumber);
        assertThat(passes.findAllByRegistrationId(kept))
                .extracting(Pass::getStatus)
                .containsExactlyInAnyOrder(PassStatus.REVOKED, PassStatus.ISSUED);
        assertThat(passes.findAllByRegistrationId(leftMeanwhile)).hasSize(1);
        assertThat(outboxCount("pass.issued", event)).isEqualTo(3);

        // Another un-cancel message (say, the reconciler's) changes nothing: the registration has a valid pass.
        listener.onMessage(
                message(stateChanged(event, "CANCELLED", "REGISTRATION_OPEN", TestClock.START.plusSeconds(120))));
        assertThat(passes.findAllByRegistrationId(kept)).hasSize(2);
    }

    @Test
    void reopeningACompletedEventReissuesItsExpiredPasses() {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        listener.onMessage(message(stateChanged(event, "REGISTRATION_CLOSED", "LIVE", TestClock.START)));
        listener.onMessage(message(stateChanged(event, "LIVE", "COMPLETED", TestClock.START.plusSeconds(60))));
        String expired = pass(registration).getPassNumber();

        listener.onMessage(message(stateChanged(event, "COMPLETED", "LIVE", TestClock.START.plusSeconds(120))));

        Pass fresh = pass(registration);
        assertThat(fresh.getStatus()).isEqualTo(PassStatus.ACTIVE);
        assertThat(fresh.getPassNumber()).isNotEqualTo(expired);
    }

    @Test
    void anAdminReissuesOneRevokedPassOnce() {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        Pass old = pass(registration);
        lifecycle.revokeByStaff(old.getId(), "admin", true, "wrong person");

        Pass fresh = lifecycle.reissueByAdmin(old.getId(), "admin");
        assertThat(fresh.getStatus()).isEqualTo(PassStatus.ISSUED);
        assertThat(fresh.getRegistrationId()).isEqualTo(registration);
        assertThat(passes.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.REVOKED);

        assertThatThrownBy(() -> lifecycle.reissueByAdmin(old.getId(), "admin"))
                .isInstanceOf(com.mitpass.common.web.error.ConflictException.class);
        assertThatThrownBy(() -> lifecycle.reissueByAdmin(fresh.getId(), "admin"))
                .isInstanceOf(com.mitpass.common.web.error.ConflictException.class);
    }

    @Test
    void completedExpiresAndLiveActivates() {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));

        listener.onMessage(message(stateChanged(event, "REGISTRATION_CLOSED", "LIVE", TestClock.START)));
        assertThat(pass(registration).getStatus()).isEqualTo(PassStatus.ACTIVE);

        listener.onMessage(message(stateChanged(event, "LIVE", "COMPLETED", TestClock.START.plusSeconds(60))));
        assertThat(pass(registration).getStatus()).isEqualTo(PassStatus.EXPIRED);
    }

    @Test
    void anOlderStateChangeIsIgnored() {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        Instant later = TestClock.START.plusSeconds(600);
        listener.onMessage(message(stateChanged(event, "REGISTRATION_OPEN", "REGISTRATION_CLOSED", later)));

        // Redelivered out of order: a cancellation that happened before the close (and was undone by event-service's
        // own state machine in reality) must not revoke passes now.
        listener.onMessage(message(stateChanged(event, "REGISTRATION_OPEN", "CANCELLED", later.minusSeconds(60))));

        assertThat(pass(registration).getStatus()).isEqualTo(PassStatus.ISSUED);
        assertThat(eventRefs.findById(event.id()).orElseThrow().getStatus()).isEqualTo("REGISTRATION_CLOSED");
    }

    @Test
    void rescheduleMovesTheTimesOnlyForward() {
        listener.onMessage(message(confirmed(event, UUID.randomUUID(), UUID.randomUUID())));
        Instant newStart = event.startsAt().plus(Duration.ofDays(1));

        listener.onMessage(message(new EventRescheduled(
                event.id(),
                event.code(),
                newStart,
                newStart.plus(Duration.ofHours(3)),
                TestClock.START.plusSeconds(5),
                "organizer")));
        listener.onMessage(message(new EventRescheduled(
                event.id(),
                event.code(),
                event.startsAt(),
                event.endsAt(),
                TestClock.START.plusSeconds(1),
                "organizer")));

        assertThat(eventRefs.findById(event.id()).orElseThrow().getStartsAt()).isEqualTo(newStart);
    }

    @Test
    void staffRevokeOfARevokedPassIsAnIllegalTransition() {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, UUID.randomUUID())));
        UUID passId = pass(registration).getId();

        Pass revoked = lifecycle.revokeByStaff(passId, "organizer-1", false, "Fake ID");
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.ORGANIZER);
        assertThat(revoked.getRevokeNote()).isEqualTo("Fake ID");

        assertThatThrownBy(() -> lifecycle.revokeByStaff(passId, "admin-1", true, null))
                .isInstanceOf(IllegalTransitionException.class);
    }

    private Pass pass(UUID registration) {
        Optional<Pass> pass = passes.findByRegistrationId(registration);
        assertThat(pass).as("pass for registration %s", registration).isPresent();
        return pass.get();
    }

    private String outboxPayload(String type, UUID passId) {
        return jdbc.sql("SELECT payload::text FROM outbox_message WHERE event_type = :type AND aggregate_id = :id")
                .param("type", type)
                .param("id", passId.toString())
                .query(String.class)
                .single();
    }

    private int outboxCount(String type, TestEvent event) {
        return jdbc.sql("SELECT count(*) FROM outbox_message WHERE event_type = :type"
                        + " AND (payload -> 'payload' ->> 'eventId')::bigint = :event")
                .param("type", type)
                .param("event", event.id())
                .query(Integer.class)
                .single();
    }

    private int revocations(UUID passId) {
        return jdbc.sql("SELECT count(*) FROM revocation WHERE pass_id = :id")
                .param("id", passId)
                .query(Integer.class)
                .single();
    }
}
