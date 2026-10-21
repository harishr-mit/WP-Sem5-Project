package com.mitpass.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.checkin.service.ScanService.OfflineScan;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Offline scans synced later (docs/plans/phase-7.md §8, owner Q2/Q3/Q12). */
@CheckinIntegrationTest
class BatchSyncTest {

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    EventRefStore events;

    @Autowired
    PassCopyStore copy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

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
    void scansRunOldestFirstAndAnswerInTheOrderSent() {
        String priyaToken = scenario.liveToken(priya);
        String arunToken = scenario.liveToken(arun);
        Instant t = clock.instant();
        clock.advance(Duration.ofMinutes(2));

        List<ScanOutcome> answers = scans.batch(
                gate.volunteerUser(),
                "phone-a",
                List.of(
                        item("b", arunToken, t.plusSeconds(20)),
                        item("a", priyaToken, t.plusSeconds(5)),
                        item("c", priyaToken, t.plusSeconds(30)))); // the same pass again, later on this phone

        assertThat(answers)
                .extracting(ScanOutcome::result)
                .containsExactly(ScanResult.ACCEPTED, ScanResult.ACCEPTED, ScanResult.DUPLICATE_OFFLINE);
        // attendance keeps the phone's time of the scan, the log the server's time of arrival
        Instant checkedIn = jdbc.sql("SELECT checked_in_at FROM attendance WHERE pass_id = ?")
                .param(priya.id())
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        assertThat(checkedIn).isEqualTo(t.plusSeconds(5));
        assertThat(jdbc.sql("SELECT count(*) FROM scan_log WHERE device_id = 'phone-a' AND offline AND received_at = ?")
                        .param(java.sql.Timestamp.from(clock.instant()))
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void aResentScanGetsItsFirstAnswerAndIsNotScannedAgain() {
        List<OfflineScan> queue = List.of(item("q-1", scenario.liveToken(priya), clock.instant()));

        assertThat(scans.batch(gate.volunteerUser(), "phone-b", queue)
                        .getFirst()
                        .result())
                .isEqualTo(ScanResult.ACCEPTED);
        assertThat(scans.batch(gate.volunteerUser(), "phone-b", queue)
                        .getFirst()
                        .result())
                .isEqualTo(ScanResult.ACCEPTED);
        assertThat(jdbc.sql("SELECT count(*) FROM scan_log WHERE pass_id = ?")
                        .param(priya.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anOnlineCheckInBeforeTheSyncWins() {
        String token = scenario.liveToken(priya);
        Instant offlineAt = clock.instant();
        clock.advance(Duration.ofSeconds(30));
        scans.scan(
                gate.volunteerUser(),
                new ScanCommand(scenario.liveToken(priya), null, gate.main().id(), "online", null, ScanMode.IN));

        ScanOutcome answer = scans.batch(gate.volunteerUser(), "phone-c", List.of(item("x", token, offlineAt)))
                .getFirst();

        assertThat(answer.result()).isEqualTo(ScanResult.DUPLICATE_OFFLINE);
        assertThat(answer.result().color()).isEqualTo(ScanResult.Color.RED);
    }

    @Test
    void theFirstPhoneToSyncWinsEvenWithTheLaterScan() {
        String token = scenario.liveToken(priya);
        Instant t = clock.instant();
        clock.advance(Duration.ofMinutes(1));

        ScanOutcome later = scans.batch(gate.volunteerUser(), "phone-d", List.of(item("d", token, t.plusSeconds(20))))
                .getFirst();
        ScanOutcome earlier = scans.batch(gate.volunteerUser(), "phone-e", List.of(item("e", token, t.plusSeconds(5))))
                .getFirst();

        assertThat(later.result()).isEqualTo(ScanResult.ACCEPTED);
        assertThat(earlier.result()).isEqualTo(ScanResult.DUPLICATE_OFFLINE);
        Instant checkedIn = jdbc.sql("SELECT checked_in_at FROM attendance WHERE pass_id = ?")
                .param(priya.id())
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        assertThat(checkedIn).as("never rewritten").isEqualTo(t.plusSeconds(20));
    }

    @Test
    void aWrongPhoneClockIsRefused() {
        String token = scenario.liveToken(priya);
        clock.advance(Duration.ofHours(13));

        List<ScanOutcome> answers = scans.batch(
                gate.volunteerUser(),
                "phone-f",
                List.of(
                        item("old", token, TestClock.START),
                        item("future", token, clock.instant().plus(Duration.ofMinutes(5)))));

        assertThat(answers)
                .extracting(ScanOutcome::result)
                .containsExactly(ScanResult.REJECTED_OFFLINE_CLOCK, ScanResult.REJECTED_OFFLINE_CLOCK);
    }

    @Test
    void aLiveCodeThatExpiredBeforeTheSyncCountsAsOfTheScan() {
        String token = scenario.liveToken(priya); // 45 s
        Instant scannedAt = clock.instant().plusSeconds(10);
        clock.advance(Duration.ofMinutes(10));

        assertThat(scans.batch(gate.volunteerUser(), "phone-g", List.of(item("g", token, scannedAt)))
                        .getFirst()
                        .result())
                .isEqualTo(ScanResult.ACCEPTED);
    }

    @Test
    void anEventCompletedAfterTheScanStillAcceptsIt() {
        String token = scenario.liveToken(priya);
        Instant scannedAt = clock.instant();
        clock.set(scannedAt.plusSeconds(90));
        String late = scenario.liveToken(arun); // shown 90 s later, after the event ended
        clock.set(scannedAt);
        events.apply(gate.event().id(), gate.event().code(), "COMPLETED", scannedAt.plusSeconds(60), scannedAt);
        clock.advance(Duration.ofMinutes(5));

        assertThat(scans.batch(gate.volunteerUser(), "phone-h", List.of(item("h", token, scannedAt)))
                        .getFirst()
                        .result())
                .isEqualTo(ScanResult.ACCEPTED);
        // but not one scanned after the event ended
        assertThat(scans.batch(gate.volunteerUser(), "phone-h", List.of(item("i", late, scannedAt.plusSeconds(90))))
                        .getFirst()
                        .result())
                .isEqualTo(ScanResult.EVENT_CLOSED);
    }

    @Test
    void theHttpEndpointAnswersEveryItem() throws Exception {
        mvc.perform(post("/api/scans/batch")
                        .header("Authorization", "Bearer " + gate.volunteerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"phone-http","scans":[
                                  {"localId":"l-1","token":"%s","gateId":%d,"scannedAt":"%s"},
                                  {"localId":"l-2","passNumber":"%s","gateId":%d,"scannedAt":"%s"}]}
                                """.formatted(
                                        scenario.liveToken(priya),
                                        gate.main().id(),
                                        clock.instant(),
                                        priya.passNumber(),
                                        gate.main().id(),
                                        clock.instant().plusSeconds(5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].localId").value("l-1"))
                .andExpect(jsonPath("$.results[0].result").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].holderName").value("Priya Raman"))
                .andExpect(jsonPath("$.results[1].result").value("DUPLICATE_OFFLINE"))
                .andExpect(jsonPath("$.results[1].color").value("RED"));
    }

    @Test
    void theManifestListsEveryPassWithItsStatusAndFirstNamesOnly() throws Exception {
        copy.markRevoked(arun.id(), arun.eventId(), arun.passNumber(), clock.instant(), "ORGANIZER", clock.instant());

        mvc.perform(get("/api/scans/manifest")
                        .param("eventId", gate.event().id().toString())
                        .header("Authorization", "Bearer " + gate.volunteerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("mitpass-pass"))
                .andExpect(jsonPath("$.doorsOpenBeforeSeconds").value(1800))
                .andExpect(jsonPath("$.sessions[0].id").value(0))
                .andExpect(jsonPath("$.passes.length()").value(2))
                .andExpect(jsonPath("$.passes[0].firstName").value("Priya"))
                .andExpect(jsonPath("$.passes[1].status").value("REVOKED"))
                .andExpect(jsonPath("$..holderEmail").doesNotExist());
        // a volunteer of another event may not take this event's list
        mvc.perform(get("/api/scans/manifest")
                        .param("eventId", gate.event().id().toString())
                        .header(
                                "Authorization",
                                "Bearer " + TestJwtIssuer.tokenFor(UUID.randomUUID(), Roles.VOLUNTEER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/scans/manifest")
                        .param("eventId", gate.event().id().toString())
                        .header(
                                "Authorization",
                                "Bearer " + TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER)))
                .andExpect(status().isOk());
    }

    private OfflineScan item(String localId, String token, Instant scannedAt) {
        return new OfflineScan(localId, token, null, gate.main().id(), scannedAt);
    }
}
