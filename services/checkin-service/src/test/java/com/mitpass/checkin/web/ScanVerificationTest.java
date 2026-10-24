package com.mitpass.checkin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.FakeEventCatalog;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.TestPassKeys;
import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One case per result code (docs/plans/phase-5.md §5, exit criterion 2), through HTTP, plus static tokens, the manual
 * path, check-out, re-entry and sessions. Every scan leaves a scan log row.
 */
@CheckinIntegrationTest
class ScanVerificationTest {

    static final String SCAN = "urn:mitpass:problem:scan/";

    @Autowired
    MockMvc mvc;

    @Autowired
    ScanScenario scenario;

    @Autowired
    TestPassKeys keys;

    @Autowired
    TestClock clock;

    @Autowired
    FakeEventCatalog catalog;

    @Autowired
    PassCopyStore copy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    Gate gate;
    PassRecord pass;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        gate = scenario.liveEvent();
        pass = scenario.pass(gate, 1, "Priya Raman");
    }

    @Test
    void accepted() throws Exception {
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.color").value("GREEN"))
                .andExpect(jsonPath("$.message").value("Welcome, Priya Raman."))
                .andExpect(jsonPath("$.attendee.passNumber").value(pass.passNumber()))
                .andExpect(jsonPath("$.gateCode").value("MAIN"))
                .andExpect(jsonPath("$.sessionId").value(0))
                .andExpect(jsonPath("$.checkedInAt").value(TestClock.START.toString()));

        assertThat(count("attendance WHERE pass_id = '" + pass.id() + "'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT state FROM presence WHERE pass_id = :id")
                        .param("id", pass.id())
                        .query(String.class)
                        .single())
                .isEqualTo("IN");
        assertThat(count(
                        "outbox_message WHERE event_type = 'attendance.marked' AND payload -> 'payload' ->> 'passId' = '"
                                + pass.id() + "'"))
                .isEqualTo(1);
        String method = jdbc.sql("SELECT method FROM attendance WHERE pass_id = :id")
                .param("id", pass.id())
                .query(String.class)
                .single();
        assertThat(method).isEqualTo("QR_LIVE");
    }

    @Test
    void everyAnswerIsCountedByResult() throws Exception {
        double accepted = scans("ACCEPTED");
        double again = scans("ALREADY_CHECKED_IN");
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN").andExpect(status().isOk());
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN").andExpect(status().isConflict());
        assertThat(scans("ACCEPTED")).isEqualTo(accepted + 1);
        assertThat(scans("ALREADY_CHECKED_IN")).isEqualTo(again + 1);
    }

    private double scans(String result) {
        Counter counter = meters.find("mitpass.scans").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void alreadyCheckedIn() throws Exception {
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN").andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(30));

        rejected(scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN"), 409, "ALREADY_CHECKED_IN", "AMBER")
                .andExpect(jsonPath("$.message").value("Already checked in at 08:30 at MAIN."))
                .andExpect(jsonPath("$.checkedInAt").value(TestClock.START.toString()))
                .andExpect(jsonPath("$.attendee.holderName").value("Priya Raman"));
        assertThat(count("scan_log WHERE pass_id = '" + pass.id() + "'")).isEqualTo(2);
    }

    @Test
    void checkedOutAndNotCheckedIn() throws Exception {
        rejected(scan(gate, scenario.liveToken(pass), null, gate.main().id(), "OUT"), 409, "NOT_CHECKED_IN", "AMBER");

        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN").andExpect(status().isOk());
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "OUT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.message").value("Priya Raman checked out."));
        assertThat(history(pass)).containsExactly("NOT_ARRIVED>IN", "IN>OUT");
    }

    @Test
    void reentryNotAllowedOnASingleEntryEvent() throws Exception {
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN").andExpect(status().isOk());
        scan(gate, scenario.liveToken(pass), null, gate.main().id(), "OUT").andExpect(status().isOk());

        rejected(scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN"), 409, "REENTRY_NOT_ALLOWED", "RED");
    }

    @Test
    void reentryAllowedAddsNoSecondAttendance() throws Exception {
        Gate open = scenario.event("LIVE", true, null);
        PassRecord p = scenario.pass(open, 1, "Arun Kumar");
        scan(open, scenario.liveToken(p), null, open.main().id(), "IN").andExpect(status().isOk());
        scan(open, scenario.liveToken(p), null, open.main().id(), "OUT").andExpect(status().isOk());

        scan(open, scenario.liveToken(p), null, open.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.reentry").value(true));

        assertThat(count("attendance WHERE pass_id = '" + p.id() + "'")).isEqualTo(1);
        assertThat(count(
                        "outbox_message WHERE event_type = 'attendance.marked' AND payload -> 'payload' ->> 'passId' = '"
                                + p.id() + "'"))
                .as("re-entry publishes nothing")
                .isEqualTo(1);
        assertThat(history(p)).containsExactly("NOT_ARRIVED>IN", "IN>OUT", "OUT>IN");
    }

    @Test
    void revoked() throws Exception {
        copy.markRevoked(pass.id(), pass.eventId(), pass.passNumber(), clock.instant(), "ORGANIZER", clock.instant());

        rejected(scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN"), 409, "REVOKED", "RED")
                .andExpect(jsonPath("$.attendee.holderName").value("Priya Raman"));
    }

    @Test
    void notYetOpenBeforeTheEventIsLive() throws Exception {
        Gate closed = scenario.event("REGISTRATION_CLOSED", false, null);
        PassRecord p = scenario.pass(closed, 1, "Arun Kumar");

        rejected(scan(closed, scenario.liveToken(p), null, closed.main().id(), "IN"), 409, "NOT_YET_OPEN", "AMBER")
                .andExpect(jsonPath("$.message").value("Doors aren't open yet (opens Sat 14 Nov, 08:20)."));
    }

    @Test
    void eventClosedAfterItCompleted() throws Exception {
        Gate done = scenario.event("COMPLETED", false, null);
        PassRecord p = scenario.pass(done, 1, "Arun Kumar");
        String staticToken = keys.staticToken(p.id(), p.eventId(), p.userId(), clock.instant(), Duration.ofHours(6));

        rejected(scan(done, staticToken, null, done.main().id(), "IN"), 409, "EVENT_CLOSED", "RED");
    }

    @Test
    void wrongEvent() throws Exception {
        Gate other = scenario.liveEvent();
        PassRecord elsewhere = scenario.pass(other, 1, "Arun Kumar");

        rejected(scan(gate, scenario.liveToken(elsewhere), null, gate.main().id(), "IN"), 409, "WRONG_EVENT", "RED")
                .andExpect(jsonPath("$.message")
                        .value("This pass is for " + other.event().title() + "."));
    }

    @Test
    void wrongGate() throws Exception {
        rejected(scan(gate, scenario.liveToken(pass), null, gate.north().id(), "IN"), 409, "WRONG_GATE", "AMBER")
                .andExpect(jsonPath("$.message").value("You're assigned to MAIN gate, not this one."))
                .andExpect(jsonPath("$.attendee").doesNotExist());
    }

    @Test
    void expiredTokenAfterTheSkewButNotWithinIt() throws Exception {
        Instant issued = clock.instant();
        String token = scenario.liveToken(pass);
        clock.advance(Duration.ofSeconds(54)); // exp = +45 s, skew 10 s
        scan(gate, token, null, gate.main().id(), "IN").andExpect(status().isOk());

        PassRecord second = scenario.pass(gate, 2, "Arun Kumar");
        String old = keys.live(second.id(), second.eventId(), second.userId(), issued);
        clock.set(issued.plusSeconds(56));
        rejected(scan(gate, old, null, gate.main().id(), "IN"), 422, "EXPIRED_TOKEN", "RED");
    }

    @Test
    void invalidSignature() throws Exception {
        rejected(
                scan(
                        gate,
                        keys.forged(pass.id(), pass.eventId(), pass.userId(), clock.instant()),
                        null,
                        gate.main().id(),
                        "IN"),
                422,
                "INVALID_SIGNATURE",
                "RED");
        rejected(scan(gate, "eyJhbGciOiJub25lIn0.e30.", null, gate.main().id(), "IN"), 422, "INVALID_SIGNATURE", "RED");
        assertThat(count("attendance WHERE pass_id = '" + pass.id() + "'")).isZero();
    }

    @Test
    void invalidPassNumber() throws Exception {
        String typo = pass.passNumber().substring(0, pass.passNumber().length() - 1)
                + (char) ('0' + (pass.passNumber().charAt(pass.passNumber().length() - 1) - '0' + 1) % 10);

        rejected(scan(gate, null, typo, gate.main().id(), "IN"), 422, "INVALID_PASS_NUMBER", "RED");
    }

    @Test
    void unknownPassWhenNeitherTheCopyNorTheOwnerHasIt() throws Exception {
        PassRecord ghost = CheckinFixtures.pass(gate.event(), 99, "Nobody");

        rejected(scan(gate, scenario.liveToken(ghost), null, gate.main().id(), "IN"), 404, "UNKNOWN_PASS", "AMBER");
    }

    @Test
    void aPassOnlyTheOwnerKnowsIsFetchedAndAccepted() throws Exception {
        PassRecord late = scenario.ownerOnlyPass(gate, 7, "Late Message");

        scan(gate, scenario.liveToken(late), null, gate.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendee.holderName").value("Late Message"));
        assertThat(copy.find(late.id())).isPresent();
    }

    @Test
    void unauthorizedScanner() throws Exception {
        catalog.unassignAll(gate.volunteer());

        rejected(scan(gate, scenario.liveToken(pass), null, gate.main().id(), "IN"), 403, "UNAUTHORIZED_SCANNER", "RED")
                .andExpect(jsonPath("$.attendee").doesNotExist());
    }

    @Test
    void aStaticTokenIsAcceptedAndLoggedAsStatic() throws Exception {
        String token = keys.staticToken(pass.id(), pass.eventId(), pass.userId(), clock.instant(), Duration.ofHours(6));

        scan(gate, token, null, gate.main().id(), "IN").andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT method FROM attendance WHERE pass_id = :id")
                        .param("id", pass.id())
                        .query(String.class)
                        .single())
                .isEqualTo("QR_STATIC");
    }

    @Test
    void manualEntryLooksUpThenChecksIn() throws Exception {
        mvc.perform(get("/api/scans/lookup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + gate.volunteerToken())
                        .param("passNumber", " " + pass.passNumber().toLowerCase() + " ")
                        .param("gateId", String.valueOf(gate.main().id()))
                        .param("deviceId", "phone-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderName").value("Priya Raman"))
                .andExpect(jsonPath("$.passStatus").value("VALID"))
                .andExpect(jsonPath("$.presence").value("NOT_ARRIVED"));

        scan(gate, null, pass.passNumber(), gate.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
        assertThat(jdbc.sql("SELECT method FROM attendance WHERE pass_id = :id")
                        .param("id", pass.id())
                        .query(String.class)
                        .single())
                .isEqualTo("MANUAL");
    }

    @Test
    void aNumberOfAnotherEventIsTheWrongEvent() throws Exception {
        Gate other = scenario.liveEvent();
        PassRecord elsewhere = scenario.pass(other, 1, "Arun Kumar");

        rejected(scan(gate, null, elsewhere.passNumber(), gate.main().id(), "IN"), 409, "WRONG_EVENT", "RED");
    }

    @Test
    void eachSessionCountsOnce() throws Exception {
        Instant now = clock.instant();
        SessionInfo s1 = CheckinFixtures.session(
                "Data preparation", now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofHours(1)));
        SessionInfo s2 =
                CheckinFixtures.session("Model training", now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(3)));
        Gate workshop = scenario.event("LIVE", false, 2, s1, s2);
        PassRecord p = scenario.pass(workshop, 1, "Arun Kumar");

        scan(workshop, scenario.liveToken(p), null, workshop.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionTitle").value("Data preparation"));
        rejected(
                scan(workshop, scenario.liveToken(p), null, workshop.main().id(), "IN"),
                409,
                "ALREADY_CHECKED_IN",
                "AMBER");
        scan(workshop, scenario.liveToken(p), null, workshop.main().id(), "OUT").andExpect(status().isOk());

        clock.set(now.plus(Duration.ofMinutes(100))); // doors of session 2
        scan(workshop, scenario.liveToken(p), null, workshop.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionTitle").value("Model training"))
                .andExpect(jsonPath("$.reentry").value(false));
        assertThat(count("attendance WHERE pass_id = '" + p.id() + "'")).isEqualTo(2);
    }

    // ---- RL11 (owner, 2026-10-16): organizers scan at any gate of their own events; elsewhere volunteer rules -------

    @Test
    void anOrganizerScansAtAnyGateOfTheirOwnEventWithoutAnAssignment() throws Exception {
        String owner = TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER); // no VOLUNTEER claim needed

        scanAs(owner, scenario.liveToken(pass), gate.north().id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.gateCode").value("NORTH"));
    }

    @Test
    void atAnEventTheyDontOrganizeAnOrganizerFollowsTheVolunteerRules() throws Exception {
        UUID organizer = UUID.randomUUID();
        String token = TestJwtIssuer.tokenFor(organizer, Roles.ORGANIZER, Roles.VOLUNTEER);
        // gate's event belongs to CheckinFixtures.ORGANIZER, not to this organizer

        rejected(scanAs(token, scenario.liveToken(pass), gate.main().id()), 403, "UNAUTHORIZED_SCANNER", "RED");

        catalog.assign(organizer, gate.event(), gate.main()); // assigned to MAIN like any volunteer
        scanAs(token, scenario.liveToken(pass), gate.main().id()).andExpect(status().isOk());
        PassRecord second = scenario.pass(gate, 2, "Arun Kumar");
        rejected(scanAs(token, scenario.liveToken(second), gate.north().id()), 409, "WRONG_GATE", "AMBER");
    }

    @Test
    void anAssignedVolunteerMayScanTheirOwnTicket() throws Exception {
        PassRecord own = new PassRecord(
                UUID.randomUUID(),
                com.mitpass.common.pass.PassNumber.of(gate.event().code(), 5),
                "ACTIVE",
                UUID.randomUUID(),
                gate.event().id(),
                gate.volunteer(),
                "Karthik Selvam",
                "karthik@mitpass.test",
                clock.instant(),
                null,
                null);
        scenario.put(own);

        scan(gate, scenario.liveToken(own), null, gate.main().id(), "IN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    ResultActions scanAs(String bearer, String token, long gateId) throws Exception {
        return mvc.perform(post("/api/scans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(token, null, gateId, "IN")));
    }

    @Test
    void studentsMayNotScanAndTheBodyIsValidated() throws Exception {
        mvc.perform(post("/api/scans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.STUDENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                body(scenario.liveToken(pass), null, gate.main().id(), "IN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:forbidden"));
        mvc.perform(post("/api/scans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + gate.volunteerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                scenario.liveToken(pass),
                                pass.passNumber(),
                                gate.main().id(),
                                "IN")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/scans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + gate.volunteerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                body(scenario.liveToken(pass), null, gate.main().id(), "LOOKUP")))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    ResultActions scan(Gate gate, String token, String number, long gateId, String mode) throws Exception {
        return mvc.perform(post("/api/scans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gate.volunteerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(token, number, gateId, mode)));
    }

    static ResultActions rejected(ResultActions actions, int status, String result, String color) throws Exception {
        return actions.andExpect(status().is(status))
                .andExpect(jsonPath("$.type").value(SCAN + result.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.result").value(result))
                .andExpect(jsonPath("$.color").value(color))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.scanId").isNotEmpty());
    }

    static String body(String token, String number, long gateId, String mode) {
        return """
                {"token":%s,"passNumber":%s,"gateId":%d,"deviceId":"phone-1","mode":"%s"}
                """.formatted(quote(token), quote(number), gateId, mode);
    }

    static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    int count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere)
                .query(Integer.class)
                .single();
    }

    java.util.List<String> history(PassRecord p) {
        return jdbc.sql("SELECT from_state || '>' || to_state FROM presence_history WHERE pass_id = :id ORDER BY id")
                .param("id", p.id())
                .query(String.class)
                .list();
    }

    static UUID any() {
        return UUID.randomUUID();
    }
}
