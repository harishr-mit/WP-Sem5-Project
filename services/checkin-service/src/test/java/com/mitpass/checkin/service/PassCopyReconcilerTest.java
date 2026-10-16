package com.mitpass.checkin.service;

import static com.mitpass.checkin.CheckinFixtures.message;
import static com.mitpass.checkin.CheckinFixtures.stateChanged;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.FakePassDirectory;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.messaging.CheckinInboxListener;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The automatic reconciliation (docs/plans/phase-5.md §4a, owner Q6): every gap a lost message leaves is closed by
 * the reconciler on its own, with nobody pressing a button. In these tests nothing reaches checkin-service by message
 * unless the test sends it; the owner's data lives only in {@link FakePassDirectory}.
 */
@CheckinIntegrationTest
class PassCopyReconcilerTest {

    @Autowired
    PassCopyReconciler reconciler;

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    FakePassDirectory directory;

    @Autowired
    PassCopyStore copy;

    @Autowired
    EventRefStore events;

    @Autowired
    CheckinInboxListener listener;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        directory.clear();
        jdbc.sql("DELETE FROM sync_cursor").update(); // the fake feed restarts at seq 1
    }

    @Test
    void aRevocationThatNeverCameByMessageIsCaughtWithinOneRun() {
        Gate gate = scenario.liveEvent();
        PassRecord pass = scenario.pass(gate, 1, "Priya Raman");
        directory.revoke(pass.id(), clock.instant()); // PassRevoked lost on the way

        assertThat(copy.find(pass.id()).orElseThrow().revoked()).isFalse();
        assertThat(reconciler.catchUpRevocations()).isEqualTo(1);

        assertThat(copy.find(pass.id()).orElseThrow().revoked()).isTrue();
        assertThat(scan(gate, pass)).isEqualTo(ScanResult.REVOKED);
        assertThat(reconciler.cursor(PassCopyReconciler.REVOCATIONS)).isEqualTo(1);
        assertThat(reconciler.catchUpRevocations())
                .as("nothing new after the cursor")
                .isZero();
    }

    @Test
    void passesThatNeverCameByMessageArePresentAfterOneWindowRun() {
        Gate gate = scenario.liveEvent();
        PassRecord a = scenario.ownerOnlyPass(gate, 1, "Priya Raman");
        PassRecord b = scenario.ownerOnlyPass(gate, 2, "Arun Kumar");

        assertThat(reconciler.syncWindow()).isGreaterThanOrEqualTo(1);

        assertThat(copy.find(a.id())).isPresent();
        assertThat(copy.find(b.id())).isPresent();
        assertThat(scanNumber(gate, b)).isEqualTo(ScanResult.ACCEPTED);
    }

    @Test
    void aLostEventStateChangeIsCorrectedFromTheOwner() {
        Gate gate = scenario.liveEvent(); // event-service says LIVE
        PassRecord pass = scenario.pass(gate, 1, "Priya Raman");
        events.apply(gate.event().id(), gate.event().code(), "REGISTRATION_CLOSED", clock.instant(), clock.instant());
        assertThat(scan(gate, pass)).as("the stale copy").isEqualTo(ScanResult.NOT_YET_OPEN);

        clock.advance(Duration.ofSeconds(1));
        reconciler.syncWindow();

        assertThat(events.status(gate.event().id())).contains("LIVE");
        assertThat(scan(gate, pass)).isEqualTo(ScanResult.ACCEPTED);
    }

    @Test
    void goingLiveSyncsTheEventsPassesRightAway() throws Exception {
        Gate gate = scenario.event("REGISTRATION_CLOSED", false, null);
        PassRecord pass = scenario.ownerOnlyPass(gate, 1, "Priya Raman");

        listener.onMessage(message(stateChanged(gate.event(), "REGISTRATION_CLOSED", "LIVE", clock.instant())));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (copy.find(pass.id()).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(copy.find(pass.id()))
                .as("synced after the LIVE message committed")
                .isPresent();
    }

    @Test
    void aTypedNumberTheCopyLacksSyncsItsEventOncePerInterval() {
        Gate gate = scenario.liveEvent();
        PassRecord pass = scenario.ownerOnlyPass(gate, 1, "Priya Raman");

        assertThat(scanNumber(gate, pass)).isEqualTo(ScanResult.ACCEPTED);

        PassRecord ghost = com.mitpass.checkin.CheckinFixtures.pass(gate.event(), 9, "Nobody");
        int before = directory.calls.get();
        assertThat(scanNumber(gate, ghost)).isEqualTo(ScanResult.UNKNOWN_PASS);
        assertThat(directory.calls.get()).as("rate-limited: synced moments ago").isEqualTo(before);
    }

    @Test
    void anOutageIsLoggedAndRetriedNextRun() {
        Gate gate = scenario.liveEvent();
        PassRecord pass = scenario.pass(gate, 1, "Priya Raman");
        directory.revoke(pass.id(), clock.instant());
        directory.down = true;

        reconciler.scheduledRevocations(); // must not throw
        reconciler.scheduledWindow();
        assertThat(copy.find(pass.id()).orElseThrow().revoked()).isFalse();

        directory.down = false;
        reconciler.scheduledRevocations();
        assertThat(copy.find(pass.id()).orElseThrow().revoked()).isTrue();
    }

    @Test
    void onlyOneInstanceRunsAJobAtATime() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                    .param("key", 0x4D49545041535301L)
                    .query(Object.class)
                    .single();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reconciler.catchUpRevocations())
                    .as("another instance holds the job")
                    .isEqualTo(-1);
        } finally {
            release.countDown();
            other.join();
        }
    }

    private ScanResult scan(Gate gate, PassRecord pass) {
        return scans.scan(
                        gate.volunteerUser(),
                        new ScanCommand(
                                scenario.liveToken(pass), null, gate.main().id(), "phone-1", null, ScanMode.IN))
                .result();
    }

    private ScanResult scanNumber(Gate gate, PassRecord pass) {
        return scans.scan(
                        gate.volunteerUser(),
                        new ScanCommand(null, pass.passNumber(), gate.main().id(), "phone-1", null, ScanMode.IN))
                .result();
    }
}
