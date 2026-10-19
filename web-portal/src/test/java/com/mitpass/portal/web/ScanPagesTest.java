package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.AttendanceSummary;
import com.mitpass.portal.client.ApiModels.Attendee;
import com.mitpass.portal.client.ApiModels.AttendeeRow;
import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.GateCount;
import com.mitpass.portal.client.ApiModels.LookupAnswer;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.PassLookup;
import com.mitpass.portal.client.ApiModels.ScanAnswer;
import com.mitpass.portal.client.ApiModels.SessionCount;
import com.mitpass.portal.client.ApiModels.SyncResult;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The P5 portal slice (docs/plans/phase-5.md §10) with the gateway mocked: the volunteer's scan page (number → lookup →
 * confirm, token → scan), the result block, and the organizer's attendance page with the CSV pass-through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScanPagesTest {

    static final String NUMBER = "TECHFEST26-000042-7";
    static final Gate MAIN = new Gate(11L, "MAIN", "Main Gate");
    static final String TOKEN = "eyJhbGciOiJFUzI1NiJ9.eyJwaWQiOiJ4In0.c2ln";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser volunteer = TestTokens.user("STUDENT", "VOLUNTEER");
    final PortalUser student = TestTokens.user("STUDENT");
    final PortalUser organizer = TestTokens.user("ORGANIZER");

    @BeforeEach
    void assignments() {
        given(gateway.myAssignments())
                .willReturn(List.of(new Assignment(1L, "TECHFEST26", "TechFest Hackathon", Instant.now(), MAIN)));
    }

    @Test
    void onlyVolunteersGetTheScanPage() throws Exception {
        mvc.perform(get("/scan")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/scan").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/scan").with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TECHFEST26 · Main Gate")));
    }

    /** RL11: an organizer gets the scan page with every gate of their own events, no assignment needed. */
    @Test
    void anOrganizerScansAtTheGatesOfTheirOwnEvents() throws Exception {
        PortalUser organizerOnly = TestTokens.user("ORGANIZER");
        given(gateway.myAssignments()).willReturn(List.of());
        given(gateway.events(null, null, organizerOnly.id(), 0))
                .willReturn(new Page<>(
                        List.of(new com.mitpass.portal.client.ApiModels.EventSummary(
                                7L, "RANG26", "Rangoli", null, null, null, null, null, null, null, null, null)),
                        0,
                        20,
                        1,
                        1));
        given(gateway.gates(7L)).willReturn(List.of(new Gate(21L, "OAT", "Open Air Theatre")));

        mvc.perform(get("/scan").with(authentication(TestTokens.login(organizerOnly))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RANG26 · Open Air Theatre (your event)")));
    }

    @Test
    void anAnyGateAssignmentListsEveryGateOfTheEvent() throws Exception {
        given(gateway.myAssignments())
                .willReturn(List.of(new Assignment(1L, "TECHFEST26", "TechFest Hackathon", Instant.now(), null)));
        given(gateway.gates(1L)).willReturn(List.of(MAIN, new Gate(12L, "NORTH", "North Gate")));

        mvc.perform(get("/scan").with(authentication(TestTokens.login(volunteer))))
                .andExpect(content().string(containsString("TECHFEST26 · North Gate")));
    }

    @Test
    void aPastedTokenIsScannedAndTheResultShownAfterARedirect() throws Exception {
        ScanAnswer accepted = answer("ACCEPTED", "GREEN", "Welcome, Priya Raman.");
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), anyString(), eq("IN"))).willReturn(accepted);

        mvc.perform(post("/scan")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("gateId", "11")
                        .param("mode", "IN")
                        .param("code", "  " + TOKEN + " "))
                .andExpect(redirectedUrl("/scan"))
                .andExpect(flash().attribute("result", accepted));

        mvc.perform(get("/scan")
                        .with(authentication(TestTokens.login(volunteer)))
                        .flashAttr("result", accepted))
                .andExpect(content().string(containsString("result result--green")))
                .andExpect(content().string(containsString("Admitted")))
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString(TestTokens.ACCESS))));
    }

    @Test
    void aRefusalIsShownInItsColour() throws Exception {
        ScanAnswer refused = answer("ALREADY_CHECKED_IN", "AMBER", "Already checked in at 09:02 at MAIN.");

        mvc.perform(get("/scan")
                        .with(authentication(TestTokens.login(volunteer)))
                        .flashAttr("result", refused))
                .andExpect(content().string(containsString("result result--amber")))
                .andExpect(content().string(containsString("ALREADY_CHECKED_IN")))
                .andExpect(content().string(containsString("Already in")));
    }

    // ---- P6: the camera page's HTMX endpoints (docs/plans/phase-6.md §8) -----------------------------------------

    @Test
    void theScannerPageHasTheCameraAndStillWorksWithoutJavaScript() throws Exception {
        mvc.perform(get("/scan").with(authentication(TestTokens.login(volunteer))))
                .andExpect(content().string(containsString("id=\"reader\"")))
                .andExpect(content().string(containsString("html5-qrcode")))
                .andExpect(content().string(containsString("/js/scanner")))
                .andExpect(content().string(containsString("data-repeat-ignore-ms=\"3000\"")))
                // the manual form posts to /scan when JavaScript is off
                .andExpect(content().string(containsString("action=\"/scan\"")))
                // fragment definitions are not rendered on the page itself
                .andExpect(content().string(not(containsString("Couldn&#39;t check"))));
    }

    @Test
    void aCameraReadAnswersTheCardAndTheFlashColour() throws Exception {
        ScanAnswer accepted = answer("ACCEPTED", "GREEN", "Welcome, Priya Raman.");
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), eq("web-0a1b2c3d"), eq("IN")))
                .willReturn(accepted);

        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("token", TOKEN)
                        .param("gateId", "11")
                        .param("mode", "IN")
                        .param("deviceId", "web-0a1b2c3d"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", containsString("\"color\":\"green\"")))
                .andExpect(content().string(containsString("Admitted")))
                .andExpect(content().string(containsString("Scan next")))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(not(containsString(TOKEN))));
    }

    @Test
    void aDeviceIdThatIsNotPlainIsReplacedByTheSessionOne() throws Exception {
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), anyString(), eq("IN")))
                .willReturn(answer("ACCEPTED", "GREEN", "ok"));

        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("token", TOKEN)
                        .param("gateId", "11")
                        .param("deviceId", "<script>"))
                .andExpect(status().isOk());
        verify(gateway)
                .scan(eq(TOKEN), isNull(), eq(11L), org.mockito.ArgumentMatchers.startsWith("portal-"), eq("IN"));
    }

    @Test
    void aRefusalIsARedFlashNotAnError() throws Exception {
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), anyString(), eq("IN")))
                .willReturn(answer("REVOKED", "RED", "This pass was revoked."));

        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("token", TOKEN)
                        .param("gateId", "11"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", containsString("\"color\":\"red\"")))
                .andExpect(content().string(containsString("result result--red")))
                .andExpect(content().string(containsString("Send them to the help desk")));
    }

    @Test
    void anApiProblemBecomesARedCardWithItsMessage() throws Exception {
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), anyString(), eq("IN")))
                .willThrow(new ApiProblemException(
                        400, "urn:mitpass:problem:validation", "gateId is unknown", null, null));

        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("token", TOKEN)
                        .param("gateId", "11"))
                .andExpect(header().string("HX-Trigger", containsString("ERROR")))
                .andExpect(content().string(containsString("gateId is unknown")));
    }

    @Test
    void theManualTabLooksANumberUpAndScansAPastedToken() throws Exception {
        PassLookup found =
                new PassLookup(NUMBER, "Priya Raman", "VALID", "NOT_ARRIVED", "TECHFEST26", "MAIN", 0L, null);
        given(gateway.lookup(eq(NUMBER), eq(11L), anyString())).willReturn(new LookupAnswer(found, null));
        given(gateway.scan(eq(TOKEN), isNull(), eq(11L), anyString(), eq("OUT")))
                .willReturn(answer("CHECKED_OUT", "GREEN", "Bye"));

        mvc.perform(post("/scan/check")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("code", " " + NUMBER + " ")
                        .param("gateId", "11"))
                .andExpect(content().string(containsString("Is this the person?")))
                .andExpect(content().string(containsString("Confirm check-in")))
                .andExpect(content().string(containsString("value=\"" + NUMBER + "\"")));
        mvc.perform(post("/scan/check")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("code", TOKEN)
                        .param("gateId", "11")
                        .param("mode", "OUT"))
                .andExpect(content().string(containsString("Checked out")));
    }

    @Test
    void theScanEndpointsNeedCsrfAndAScannerRole() throws Exception {
        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(volunteer)))
                        .param("token", TOKEN)
                        .param("gateId", "11"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/scan/submit")
                        .with(authentication(TestTokens.login(TestTokens.user("STUDENT"))))
                        .with(csrf())
                        .param("token", TOKEN)
                        .param("gateId", "11"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTypedNumberIsLookedUpThenConfirmed() throws Exception {
        PassLookup found =
                new PassLookup(NUMBER, "Priya Raman", "VALID", "NOT_ARRIVED", "TECHFEST26", "MAIN", 0L, null);
        given(gateway.lookup(eq(NUMBER), eq(11L), anyString())).willReturn(new LookupAnswer(found, null));
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/scan")
                        .session(session)
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("gateId", "11")
                        .param("code", NUMBER))
                .andExpect(redirectedUrl("/scan/confirm"))
                .andExpect(flash().attribute("lookup", found));
        verify(gateway, never()).scan(any(), any(), anyLong(), any(), any());

        mvc.perform(get("/scan/confirm")
                        .session(session)
                        .with(authentication(TestTokens.login(volunteer)))
                        .flashAttr("lookup", found))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(containsString("Confirm check-in")));

        ScanAnswer accepted = answer("ACCEPTED", "GREEN", "Welcome, Priya Raman.");
        given(gateway.scan(isNull(), eq(NUMBER), eq(11L), anyString(), eq("IN")))
                .willReturn(accepted);
        mvc.perform(post("/scan/confirm")
                        .session(session)
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("passNumber", NUMBER)
                        .param("gateId", "11")
                        .param("mode", "IN"))
                .andExpect(redirectedUrl("/scan"))
                .andExpect(flash().attribute("result", accepted));
    }

    @Test
    void aRefusedLookupGoesStraightBackWithTheResult() throws Exception {
        ScanAnswer typo = answer("INVALID_PASS_NUMBER", "RED", "That pass number has a typo (check digit).");
        given(gateway.lookup(eq("TECHFEST26-000042-0"), eq(11L), anyString())).willReturn(new LookupAnswer(null, typo));

        mvc.perform(post("/scan")
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf())
                        .param("gateId", "11")
                        .param("code", "TECHFEST26-000042-0"))
                .andExpect(redirectedUrl("/scan"))
                .andExpect(flash().attribute("result", typo));
    }

    @Test
    void theConfirmPageWithoutALookupGoesBackAndPostsNeedCsrf() throws Exception {
        mvc.perform(get("/scan/confirm").with(authentication(TestTokens.login(volunteer))))
                .andExpect(redirectedUrl("/scan"));
        mvc.perform(post("/scan")
                        .with(authentication(TestTokens.login(volunteer)))
                        .param("gateId", "11")
                        .param("code", TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizerSeesAttendanceAndDownloadsTheCsv() throws Exception {
        given(gateway.attendanceSummary(1L))
                .willReturn(new AttendanceSummary(
                        1L,
                        "TECHFEST26",
                        "TechFest Hackathon",
                        3L,
                        2L,
                        66.7,
                        2L,
                        List.of(new SessionCount(0L, "Whole event", 2L, 66.7)),
                        List.of(new GateCount("MAIN", 2L)),
                        Instant.parse("2026-11-14T03:32:00Z")));
        given(gateway.attendees(1L))
                .willReturn(new Page<>(
                        List.of(new AttendeeRow(
                                UUID.randomUUID(),
                                NUMBER,
                                "Priya Raman",
                                "p@gmail.com",
                                "VALID",
                                1,
                                Instant.parse("2026-11-14T03:30:00Z"),
                                "MAIN",
                                "IN")),
                        0,
                        100,
                        1,
                        1));
        given(gateway.certificates(1L)).willReturn(List.of());
        given(gateway.event(1L)).willReturn(PortalPagesTest.detail(1, List.of(), "LIVE", List.of()));
        given(gateway.attendanceCsv(1L))
                .willReturn(new Binary(
                        "\"passNumber\"\r\n".getBytes(StandardCharsets.UTF_8),
                        "text/csv",
                        "attachment; filename=\"TECHFEST26-attendance.csv\""));

        mvc.perform(get("/organizer/events/1/attendance").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("67% of valid passes")))
                .andExpect(content().string(containsString(NUMBER)))
                .andExpect(content().string(containsString("Export attendance CSV")))
                // the manual sync is an admin tool only (D14)
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Sync passes now"))));
        mvc.perform(get("/organizer/events/1/attendance")
                        .with(authentication(TestTokens.login(TestTokens.user("ADMIN")))))
                .andExpect(content().string(containsString("Sync passes now")));
        // Refresh (HTMX) gets only the board
        mvc.perform(get("/organizer/events/1/attendance")
                        .with(authentication(TestTokens.login(organizer)))
                        .header("HX-Request", "true"))
                .andExpect(content().string(containsString("id=\"attendance\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))));
        mvc.perform(get("/organizer/events/1/attendance.csv").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"TECHFEST26-attendance.csv\""))
                .andExpect(content().string("\"passNumber\"\r\n"));
        mvc.perform(get("/organizer/events/1/attendance").with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncIsPostRedirectGet() throws Exception {
        given(gateway.syncAttendance(1L)).willReturn(new SyncResult(1L, 3, 1));

        mvc.perform(post("/organizer/events/1/attendance/sync")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf()))
                .andExpect(redirectedUrl("/organizer/events/1/attendance"))
                .andExpect(flash().attribute("message", "Synced 3 pass(es) from pass-service (1 revoked)."));
    }

    static ScanAnswer answer(String result, String color, String message) {
        return new ScanAnswer(
                UUID.randomUUID(),
                result,
                message,
                color,
                new Attendee(UUID.randomUUID(), NUMBER, "Priya Raman"),
                "TECHFEST26",
                "MAIN",
                0L,
                null,
                Instant.parse("2026-11-14T03:30:00Z"),
                false);
    }
}
