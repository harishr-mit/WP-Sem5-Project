package com.mitpass.checkin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.checkin.service.ScanService;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reports (docs/plans/phase-5.md §4, exit criterion 9) on a three-session workshop that needs 2 sessions for a
 * certificate: Priya attends sessions 1 and 2, Arun only session 1, Meena never comes, a fourth pass is revoked.
 */
@CheckinIntegrationTest
class AttendanceReportTest {

    static final String ORGANIZER = TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER);

    @Autowired
    MockMvc mvc;

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    PassCopyStore copy;

    @Autowired
    TestClock clock;

    Gate gate;
    PassRecord priya;
    PassRecord arun;
    PassRecord meena;
    Instant t0;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        t0 = clock.instant();
        SessionInfo s1 = CheckinFixtures.session("Data preparation", t0, t0.plus(Duration.ofHours(1)));
        SessionInfo s2 =
                CheckinFixtures.session("Model training", t0.plus(Duration.ofHours(2)), t0.plus(Duration.ofHours(3)));
        SessionInfo s3 =
                CheckinFixtures.session("Deployment", t0.plus(Duration.ofHours(4)), t0.plus(Duration.ofHours(5)));
        gate = scenario.event("LIVE", false, 2, s1, s2, s3);
        priya = scenario.pass(gate, 1, "Priya Raman");
        arun = scenario.pass(gate, 2, "=HYPERLINK(\"http://x\")"); // a hostile name, for the CSV
        meena = scenario.pass(gate, 3, "Meena \"MJ\" Joseph");
        PassRecord revoked = scenario.pass(gate, 4, "Revoked Person");
        copy.markRevoked(revoked.id(), revoked.eventId(), revoked.passNumber(), t0, "ORGANIZER", t0);

        in(priya);
        in(arun);
        clock.set(t0.plus(Duration.ofMinutes(130))); // session 2
        in(priya);
    }

    @Test
    void summaryCountsPassesSessionsAndGates() throws Exception {
        mvc.perform(get("/api/attendance/events/{id}/summary", gate.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validPasses").value(3))
                .andExpect(jsonPath("$.checkedIn").value(2))
                .andExpect(jsonPath("$.percent").value(66.7))
                .andExpect(jsonPath("$.insideNow").value(2))
                .andExpect(jsonPath("$.sessions.length()").value(3))
                .andExpect(jsonPath("$.sessions[0].title").value("Data preparation"))
                .andExpect(jsonPath("$.sessions[0].checkedIn").value(2))
                .andExpect(jsonPath("$.sessions[1].checkedIn").value(1))
                .andExpect(jsonPath("$.sessions[2].checkedIn").value(0))
                .andExpect(jsonPath("$.gates[0].gateCode").value("MAIN"))
                .andExpect(jsonPath("$.gates[0].checkedIn").value(3))
                .andExpect(jsonPath("$.lastScanAt").isNotEmpty());
    }

    @Test
    void attendeesArePagedAndFilteredByPresence() throws Exception {
        mvc.perform(get("/api/attendance/events/{id}/attendees", gate.event().id())
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].passNumber").value(priya.passNumber()))
                .andExpect(jsonPath("$.content[0].sessionsAttended").value(2))
                .andExpect(jsonPath("$.content[0].presence").value("IN"));
        mvc.perform(get("/api/attendance/events/{id}/attendees", gate.event().id())
                        .param("presence", "NOT_ARRIVED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(jsonPath("$.totalElements").value(2)); // Meena and the revoked pass
    }

    @Test
    void csvHasEveryPassQuotedAndDefusesFormulas() throws Exception {
        String csv = mvc.perform(get(
                                "/api/attendance/events/{id}/export.csv",
                                gate.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + gate.event().code() + "-attendance.csv\""))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).startsWith("\"passNumber\",\"holderName\"");
        assertThat(lines[1])
                .startsWith("\"" + priya.passNumber() + "\",\"Priya Raman\"")
                .contains("\"2\",\"3\"");
        assertThat(lines[2]).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(lines[3]).contains("\"Meena \"\"MJ\"\" Joseph\"");
        assertThat(lines[1]).contains("\"2026-11-14 08:30:00\""); // first check-in in campus time
    }

    @Test
    void certificatesNeedTwoOfThreeSessions() throws Exception {
        mvc.perform(get("/api/attendance/events/{id}/certificates", gate.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].holderName").value("Priya Raman"))
                .andExpect(jsonPath("$[0].sessionsAttended").value(2))
                .andExpect(jsonPath("$[0].required").value(2));
    }

    @Test
    void aStudentSeesTheirOwnAttendance() throws Exception {
        mvc.perform(get("/api/attendance/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + TestJwtIssuer.tokenFor(arun.userId(), Roles.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].passNumber").value(arun.passNumber()))
                .andExpect(jsonPath("$[0].sessionsAttended").value(1))
                .andExpect(jsonPath("$[0].sessionsTotal").value(3))
                .andExpect(jsonPath("$[0].sessionsRequired").value(2))
                .andExpect(jsonPath("$[0].certificateEligible").value(false));
    }

    /** Bug found by the security matrix: no scans yet meant max(received_at) = NULL and a 500. */
    @Test
    void anEventWithoutScansHasAnEmptySummaryAndCsv() throws Exception {
        Gate quiet = scenario.liveEvent();
        scenario.pass(quiet, 1, "Not Yet");

        mvc.perform(get("/api/attendance/events/{id}/summary", quiet.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validPasses").value(1))
                .andExpect(jsonPath("$.checkedIn").value(0))
                .andExpect(jsonPath("$.percent").value(0.0))
                .andExpect(jsonPath("$.sessions[0].title").value("Whole event"))
                .andExpect(jsonPath("$.lastScanAt").doesNotExist());
        mvc.perform(get("/api/attendance/events/{id}/export.csv", quiet.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(status().isOk());
    }

    @Test
    void anotherOrganizerIsRefused() throws Exception {
        mvc.perform(get("/api/attendance/events/{id}/summary", gate.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.ORGANIZER)))
                .andExpect(status().isForbidden());
    }

    private void in(PassRecord pass) {
        ScanResult result = scans.scan(
                        gate.volunteerUser(),
                        new ScanCommand(
                                scenario.liveToken(pass), null, gate.main().id(), "phone-1", null, ScanMode.IN))
                .result();
        assertThat(result).isEqualTo(ScanResult.ACCEPTED);
    }
}
