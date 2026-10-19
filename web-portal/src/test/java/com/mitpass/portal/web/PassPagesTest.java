package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The P4 portal slice (docs/plans/phase-4.md §10) with the gateway mocked: wallet, pass page, proxies, revoke. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassPagesTest {

    static final Instant START = Instant.parse("2026-11-14T03:30:00Z");
    static final UUID PASS_ID = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final UUID REG_ID = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    /** A JWS would start like this; the pass page must never contain one (the token stays in the image). */
    static final String JWS_PATTERN = "(?s).*eyJ[A-Za-z0-9_-]+\\.eyJ.*";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser student = TestTokens.user("STUDENT");
    final PortalUser organizer = TestTokens.user("ORGANIZER");

    @Test
    void walletNeedsALogin() throws Exception {
        mvc.perform(get("/my/passes")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/my/passes/" + PASS_ID + "/qr.png")).andExpect(status().is3xxRedirection());
    }

    @Test
    void walletListsPassesWithTheirNumbers() throws Exception {
        given(gateway.myPasses()).willReturn(new Page<>(List.of(pass("ACTIVE")), 0, 50, 1, 1));

        mvc.perform(get("/my/passes").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TECHFEST26-000042-7")))
                .andExpect(content().string(containsString("/my/passes/" + PASS_ID)));
    }

    @Test
    void activePassShowsTheRefreshingQrImageButNeverAToken() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE"));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/my/passes/" + PASS_ID + "/qr.svg")))
                .andExpect(content().string(containsString("/js/pass-card")))
                .andExpect(content().string(containsString("class=\"ring\"")))
                .andExpect(content().string(containsString("ticket.pdf")))
                .andExpect(content().string(not(matchesPattern(JWS_PATTERN))))
                .andExpect(content().string(not(containsString(TestTokens.ACCESS))));
    }

    /**
     * Found in the two-phone demo (2026-10-19): at card size a scanning phone had to come so close it couldn't focus.
     * The code is a button that shows it full screen on white; without JavaScript it is still the plain image.
     */
    @Test
    void theLiveCodeCanBeShownFullScreenForTheScanner() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE"));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-qr-zoom")))
                .andExpect(content().string(containsString("aria-label=\"Show the code full screen\"")))
                .andExpect(content().string(containsString("Tap the code to make it bigger")));
        mvc.perform(get("/js/pass-card.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is-zoomed")));
    }

    /**
     * RL6 regression: Thymeleaf's JavaScript inlining drops the text after an inlined expression, so
     * {@code /*[[${refreshSeconds}]]*&#47; 30 * 1000} rendered as {@code 30}: the image reloaded every 30 ms. Since
     * P6 the interval is a rendered attribute (no inlining at all), and the script refuses anything under 5 s.
     */
    @Test
    void liveQrRefreshesEveryThirtySecondsNotEveryThirtyMilliseconds() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE"));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-refresh-ms=\"30000\"")))
                .andExpect(content().string(not(containsString("th:inline"))))
                .andExpect(content().string(not(containsString("refreshMillis ="))));
        mvc.perform(get("/js/pass-card.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dataset.refreshMs")))
                .andExpect(content().string(containsString("refreshMs >= 5000")));
    }

    @Test
    void theCardShowsTheSvgCodeWithACspAndKeepsThePngForOtherCallers() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes();
        given(gateway.liveQrSvg(PASS_ID)).willReturn(new Binary(svg, "image/svg+xml", null));

        mvc.perform(get("/my/passes/" + PASS_ID + "/qr.svg").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(svg));
    }

    @Test
    void theWalletPutsValidPassesFirst() throws Exception {
        Pass revoked = pass("REVOKED");
        Pass active = pass("ACTIVE");
        given(gateway.myPasses()).willReturn(new Page<>(List.of(revoked, active), 0, 50, 2, 1));

        String body = mvc.perform(get("/my/passes").with(authentication(TestTokens.login(student))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body.indexOf("Active")).isLessThan(body.indexOf("Revoked"));
    }

    @Test
    void thePassCardTakesTheEventsColourAndVenue() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE"));
        var event = PortalPagesTest.detail(1, List.of(), "LIVE", List.of());
        given(gateway.event(1L))
                .willReturn(new com.mitpass.portal.client.ApiModels.EventDetail(
                        event.id(),
                        event.code(),
                        event.title(),
                        event.description(),
                        event.category(),
                        new com.mitpass.portal.client.ApiModels.Venue(3L, "Main Auditorium", "Block A"),
                        event.department(),
                        event.capacity(),
                        event.startsAt(),
                        event.endsAt(),
                        event.registrationOpensAt(),
                        event.registrationClosesAt(),
                        event.status(),
                        event.organizerId(),
                        event.organizerName(),
                        event.eligibleDepartments(),
                        event.minYear(),
                        event.maxYear(),
                        event.allowedTransitions(),
                        event.seatsTaken(),
                        event.seatsLeft(),
                        event.version(),
                        event.sessions(),
                        true,
                        "#1D4E89",
                        false,
                        null));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("--pass-color:#1D4E89")))
                .andExpect(content().string(containsString("Main Auditorium")))
                .andExpect(content().string(containsString("MP&lt;TECHFEST26&lt;&lt;RAMAN&lt;PRIYA")));
    }

    @Test
    void aCheckedInSingleEntryPassSaysSo() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE", START, START));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Checked in")))
                .andExpect(content().string(containsString("single entry: used")));
    }

    @Test
    void issuedPassSaysWhenTheCodeUnlocks() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ISSUED"));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("Code unlocks")))
                .andExpect(content().string(not(containsString("qr.svg"))));
    }

    @Test
    void revokedPassShowsNoCodeAndNoTicket() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("REVOKED"));

        mvc.perform(get("/my/passes/" + PASS_ID).with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("was revoked")))
                .andExpect(content().string(not(containsString("qr.svg"))))
                .andExpect(content().string(not(containsString("ticket.pdf"))));
    }

    @Test
    void qrImageIsPassedThroughUncached() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        given(gateway.liveQrPng(PASS_ID)).willReturn(new Binary(png, "image/png", null));

        mvc.perform(get("/my/passes/" + PASS_ID + "/qr.png").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(png));
    }

    @Test
    void aLockedOrRevokedCodeIsA409WithoutBody() throws Exception {
        given(gateway.liveQrPng(PASS_ID))
                .willThrow(new ApiProblemException(409, "urn:mitpass:problem:pass-not-valid", "x", null, null));

        mvc.perform(get("/my/passes/" + PASS_ID + "/qr.png").with(authentication(TestTokens.login(student))))
                .andExpect(status().isConflict());
    }

    @Test
    void ticketIsPassedThroughWithItsFileName() throws Exception {
        given(gateway.ticketPdf(PASS_ID))
                .willReturn(new Binary(
                        "%PDF".getBytes(), "application/pdf", "attachment; filename=\"TECHFEST26-000042-7.pdf\""));

        mvc.perform(get("/my/passes/" + PASS_ID + "/ticket.pdf").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("TECHFEST26-000042-7.pdf")));
    }

    @Test
    void organizerSeesRegistrationsWithPassesAndRevokeButtons() throws Exception {
        given(gateway.event(1L)).willReturn(PortalPagesTest.detail(1, List.of(), "REGISTRATION_OPEN", List.of()));
        given(gateway.eventRegistrations(1L)).willReturn(new Page<>(List.of(registration()), 0, 100, 1, 1));
        given(gateway.eventPasses(1L)).willReturn(new Page<>(List.of(pass("ACTIVE")), 0, 100, 1, 1));

        mvc.perform(get("/organizer/events/1/registrations").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(containsString("TECHFEST26-000042-7")))
                .andExpect(content().string(containsString("/organizer/passes/" + PASS_ID + "/revoke")));
    }

    @Test
    void studentsCannotOpenTheOrganizerPassPages() throws Exception {
        mvc.perform(get("/organizer/events/1/registrations").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/organizer/passes/" + PASS_ID + "/revoke")
                        .with(csrf())
                        .with(authentication(TestTokens.login(student)))
                        .param("eventId", "1"))
                .andExpect(status().isForbidden());
        verify(gateway, never()).revokePass(any(), any());
    }

    @Test
    void revokeIsPostRedirectGetAndNeedsCsrf() throws Exception {
        Pass revoked = pass("REVOKED");
        given(gateway.revokePass(eq(PASS_ID), eq("Fake ID"))).willReturn(revoked);

        mvc.perform(post("/organizer/passes/" + PASS_ID + "/revoke")
                        .with(authentication(TestTokens.login(organizer)))
                        .param("eventId", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/organizer/passes/" + PASS_ID + "/revoke")
                        .with(csrf())
                        .with(authentication(TestTokens.login(organizer)))
                        .param("eventId", "1")
                        .param("note", "Fake ID")
                        .param("confirmed", "true"))
                .andExpect(redirectedUrl("/organizer/events/1/registrations"))
                .andExpect(flash().attribute("message", "Pass TECHFEST26-000042-7 is now Revoked."));
    }

    /** RL9: revoking asks first, showing whose pass it is; nothing is revoked on the first click. */
    @Test
    void revokeAsksForConfirmationFirst() throws Exception {
        given(gateway.pass(PASS_ID)).willReturn(pass("ACTIVE"));

        mvc.perform(post("/organizer/passes/" + PASS_ID + "/revoke")
                        .with(csrf())
                        .with(authentication(TestTokens.login(organizer)))
                        .param("eventId", "1")
                        .param("note", "Fake ID"))
                .andExpect(redirectedUrl("/organizer/passes/" + PASS_ID + "/revoke/confirm?eventId=1&note=Fake+ID"));
        verify(gateway, never()).revokePass(any(), any());

        mvc.perform(get("/organizer/passes/" + PASS_ID + "/revoke/confirm")
                        .param("eventId", "1")
                        .param("note", "Fake ID")
                        .with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(containsString("TECHFEST26-000042-7")))
                .andExpect(content().string(containsString("name=\"confirmed\" value=\"true\"")))
                .andExpect(content().string(containsString("value=\"Fake ID\"")));
    }

    static Pass pass(String status) {
        return pass(status, null, null);
    }

    static Pass pass(String status, Instant checkedInAt, Instant usedAt) {
        return new Pass(
                PASS_ID,
                "TECHFEST26-000042-7",
                status,
                REG_ID,
                1L,
                "TECHFEST26",
                "TechFest Hackathon",
                START,
                START.plusSeconds(8 * 3600),
                "Priya Raman",
                START.minusSeconds(86_400),
                START.minusSeconds(150 * 60),
                "REVOKED".equals(status) ? START : null,
                "REVOKED".equals(status) ? "ORGANIZER" : null,
                null,
                checkedInAt,
                usedAt);
    }

    static Registration registration() {
        return new Registration(
                REG_ID,
                1L,
                "TECHFEST26",
                "TechFest Hackathon",
                START,
                "CONFIRMED",
                null,
                false,
                START,
                null,
                "Priya Raman",
                "priya@student.test");
    }
}
