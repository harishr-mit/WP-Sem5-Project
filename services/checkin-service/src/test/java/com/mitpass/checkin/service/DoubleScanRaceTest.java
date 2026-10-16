package com.mitpass.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.domain.Presence;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.repository.AttendanceRepository;
import com.mitpass.checkin.repository.PresenceChangeRepository;
import com.mitpass.checkin.repository.PresenceRepository;
import com.mitpass.checkin.repository.ScanLogRepository;
import com.mitpass.checkin.verify.ScanContext;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.checkin.verify.ScanPipeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two volunteers scanning the same pass in the same millisecond (PROJECT.md §P5, exit criterion 3): N = 20 scans
 * released at once give exactly 1 ACCEPTED and 19 ALREADY_CHECKED_IN, one attendance row, 20 scan log rows and one
 * {@code AttendanceMarked}. A second test takes the row lock away to show the unique constraint alone still holds.
 */
@CheckinIntegrationTest
class DoubleScanRaceTest {

    static final int SCANS = 20;

    @Autowired
    ScanService scans;

    @Autowired
    ScanScenario scenario;

    @Autowired
    TestClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ScanPipeline pipeline;

    @Autowired
    PresenceRepository presences;

    @Autowired
    AttendanceRepository attendances;

    @Autowired
    PresenceChangeRepository history;

    @Autowired
    ScanLogRepository logs;

    @Autowired
    CheckinEvents events;

    @Autowired
    ScanOutcomes outcomes;

    @Autowired
    TransactionTemplate tx;

    Gate gate;
    PassRecord pass;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        gate = scenario.liveEvent();
        pass = scenario.pass(gate, 1, "Priya Raman");
    }

    @RepeatedTest(3)
    void twentySimultaneousScansGiveExactlyOneAccepted() throws Exception {
        String token = scenario.liveToken(pass);

        List<ScanOutcome> results = race(i -> () -> scans.scan(
                gate.volunteerUser(), new ScanCommand(token, null, gate.main().id(), "phone-" + i, null, ScanMode.IN)));

        Map<ScanResult, Long> byResult =
                results.stream().collect(Collectors.groupingBy(ScanOutcome::result, Collectors.counting()));
        assertThat(byResult)
                .containsOnly(
                        Map.entry(ScanResult.ACCEPTED, 1L), Map.entry(ScanResult.ALREADY_CHECKED_IN, (long) SCANS - 1));
        assertThat(count("attendance WHERE pass_id = '" + pass.id() + "'")).isEqualTo(1);
        assertThat(count("scan_log WHERE pass_id = '" + pass.id() + "'")).isEqualTo(SCANS);
        assertThat(count("presence_history WHERE pass_id = '" + pass.id() + "'"))
                .isEqualTo(1);
        assertThat(count("outbox_message WHERE event_type = 'attendance.marked'"
                        + " AND payload -> 'payload' ->> 'passId' = '" + pass.id() + "'"))
                .isEqualTo(1);
    }

    @Test
    void withoutTheRowLockTheUniqueConstraintStillAdmitsOnlyOne() throws Exception {
        CheckInTransaction unlocked = new CheckInTransaction(presences, attendances, history, logs, events, outcomes) {
            @Override
            protected Presence lockPresence(PassRef ref, Instant now) {
                presences.createIfMissing(ref.passId(), ref.eventId(), now);
                return presences.findById(ref.passId()).orElseThrow(); // no FOR UPDATE
            }
        };
        String token = scenario.liveToken(pass);

        List<String> results = race(i -> () -> {
            ScanContext scan = new ScanContext(
                    gate.volunteerUser(),
                    new ScanCommand(token, null, gate.main().id(), "phone-" + i, null, ScanMode.IN),
                    clock.instant());
            assertThat(pipeline.run(scan)).isEmpty();
            try {
                return tx.execute(status -> unlocked.checkIn(scan)).result().name();
            } catch (DataIntegrityViolationException ex) {
                return "UNIQUE_VIOLATION";
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
                return "OPTIMISTIC_LOCK";
            }
        });

        assertThat(results.stream().filter("ACCEPTED"::equals).count()).isEqualTo(1);
        assertThat(count("attendance WHERE pass_id = '" + pass.id() + "'")).isEqualTo(1);
    }

    private <T> List<T> race(Function<Integer, Callable<T>> scan) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(SCANS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < SCANS; i++) {
                Callable<T> body = scan.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return body.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere)
                .query(Integer.class)
                .single();
    }
}
