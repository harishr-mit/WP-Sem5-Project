package com.mitpass.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Redis out of reach: scans are still checked in, and the live view is read from PostgreSQL (D16). */
@CheckinIntegrationTest
class LiveStatsRedisDownTest {

    /** Every Redis call starts with this one, so it stands for "Redis is down". */
    @MockitoBean
    StringRedisTemplate redis;

    @BeforeEach
    void redisIsDown() {
        given(redis.hasKey(any())).willThrow(new RedisConnectionFailureException("connection refused"));
    }

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    LiveStats live;

    @Autowired
    TestClock clock;

    @Test
    void aScanNeverFailsBecauseOfRedis() {
        clock.set(TestClock.START);
        Gate gate = scenario.liveEvent();
        PassRecord pass = scenario.pass(gate, 1, "Priya Raman");

        ScanResult result = scans.scan(
                        gate.volunteerUser(),
                        new ScanCommand(
                                scenario.liveToken(pass), null, gate.main().id(), "dev", null, ScanMode.IN))
                .result();

        assertThat(result).isEqualTo(ScanResult.ACCEPTED);
        assertThat(live.gateCheckedIn(gate.event().id(), "MAIN")).isNull();
        var snapshot = live.snapshot(gate.event().id(), 50);
        assertThat(snapshot.checkedIn()).isEqualTo(1);
        assertThat(snapshot.inside()).isEqualTo(1);
        assertThat(snapshot.recent()).hasSize(1);
    }
}
