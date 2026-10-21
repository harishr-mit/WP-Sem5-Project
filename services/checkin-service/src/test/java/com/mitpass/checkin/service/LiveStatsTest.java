package com.mitpass.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.service.LiveStats.GateCount;
import com.mitpass.checkin.service.LiveStats.LiveSnapshot;
import com.mitpass.checkin.service.LiveStats.RecentScan;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The live counters follow the scans, and a rebuild from PostgreSQL gives the same numbers (D16). */
@CheckinIntegrationTest
class LiveStatsTest {

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    LiveStats live;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    TestClock clock;

    Gate gate;
    PassRecord priya;
    PassRecord arun;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        gate = scenario.liveEvent();
        priya = scenario.pass(gate, 1, "Priya Raman");
        arun = scenario.pass(gate, 2, "Arun Kumar");
    }

    @Test
    void countersFollowTheScans() {
        assertThat(scan(priya, ScanMode.IN)).isEqualTo(ScanResult.ACCEPTED);
        clock.advance(Duration.ofSeconds(20));
        assertThat(scan(arun, ScanMode.IN)).isEqualTo(ScanResult.ACCEPTED);
        assertThat(scan(arun, ScanMode.IN)).isEqualTo(ScanResult.ALREADY_CHECKED_IN);
        assertThat(scan(priya, ScanMode.OUT)).isEqualTo(ScanResult.CHECKED_OUT);

        LiveSnapshot snapshot = live.snapshot(gate.event().id(), 100);
        assertThat(snapshot.confirmed()).isEqualTo(2);
        assertThat(snapshot.checkedIn()).isEqualTo(2);
        assertThat(snapshot.inside()).isEqualTo(1);
        assertThat(snapshot.capacity()).isEqualTo(100);
        assertThat(snapshot.gates()).containsExactly(new GateCount("MAIN", 2));
        assertThat(snapshot.arrivals()).hasSize(1);
        assertThat(snapshot.arrivals().getFirst().count()).isEqualTo(2);
        assertThat(snapshot.recent())
                .extracting(RecentScan::firstName, RecentScan::code, RecentScan::color)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Priya", "CHECKED_OUT", "GREEN"),
                        org.assertj.core.groups.Tuple.tuple("Arun", "ALREADY_CHECKED_IN", "AMBER"),
                        org.assertj.core.groups.Tuple.tuple("Arun", "ACCEPTED", "GREEN"),
                        org.assertj.core.groups.Tuple.tuple("Priya", "ACCEPTED", "GREEN"));
        assertThat(live.gateCheckedIn(gate.event().id(), "MAIN")).isEqualTo(2);
    }

    @Test
    void aRebuildFromTheDatabaseGivesTheSameNumbers() {
        // a second apart, as real scans are: the rebuild orders the ticker by time, and equal times have no order
        // (flaky on CI with a frozen test clock, 2026-10-21)
        scan(priya, ScanMode.IN);
        clock.advance(Duration.ofSeconds(1));
        scan(arun, ScanMode.IN);
        clock.advance(Duration.ofSeconds(1));
        scan(priya, ScanMode.OUT);
        LiveSnapshot before = live.snapshot(gate.event().id(), 100);

        live.forget(gate.event().id()); // as after a Redis restart

        LiveSnapshot after = live.snapshot(gate.event().id(), 100);
        assertThat(after.checkedIn()).isEqualTo(before.checkedIn());
        assertThat(after.inside()).isEqualTo(before.inside());
        assertThat(after.gates()).isEqualTo(before.gates());
        assertThat(after.arrivals()).isEqualTo(before.arrivals());
        assertThat(after.recent())
                .extracting(RecentScan::code)
                .isEqualTo(before.recent().stream().map(RecentScan::code).toList());
    }

    @Test
    void aScanThatTriggersTheRebuildIsCountedOnce() {
        scan(priya, ScanMode.IN);
        redis.delete("mitpass:live:" + gate.event().id() + ":fresh"); // the periodic rebuild is due

        scan(arun, ScanMode.IN); // rebuilds (and so already sees itself), then must not add itself again

        LiveSnapshot snapshot = live.snapshot(gate.event().id(), 100);
        assertThat(snapshot.checkedIn()).isEqualTo(2);
        assertThat(snapshot.gates()).containsExactly(new GateCount("MAIN", 2));
        assertThat(snapshot.arrivals().getFirst().count()).isEqualTo(2);
    }

    private ScanResult scan(PassRecord pass, ScanMode mode) {
        return scans.scan(
                        gate.volunteerUser(),
                        new ScanCommand(
                                scenario.liveToken(pass), null, gate.main().id(), "live-test", null, mode))
                .result();
    }
}
