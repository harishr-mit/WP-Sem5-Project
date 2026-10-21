package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.EventQuery;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The student's catalogue and registration flow in P6 (T08.4): HTMX live search answering a fragment, the URL that
 * follows the search, PRG to "You're in" (screen 3), and the polls that stop with HTTP 286 once there is an answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventPagesTest {

    static final UUID PASS_ID = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser student = TestTokens.user("STUDENT");

    @BeforeEach
    void catalogue() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.departments()).willReturn(List.of());
        given(gateway.search(any())).willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 12, 30, 3));
        given(gateway.event(1L)).willReturn(PortalPagesTest.detail(1, List.of(), "REGISTRATION_OPEN", List.of()));
        given(gateway.myPasses()).willReturn(new Page<>(List.of(), 0, 50, 0, 0));
    }

    // ---- add to calendar (P7) ----------------------------------------------------------------------------------

    @Test
    void anyoneCanAddTheEventToTheirCalendar() throws Exception {
        byte[] ics = "BEGIN:VCALENDAR END:VCALENDAR".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        given(gateway.calendar(1L))
                .willReturn(new com.mitpass.portal.client.ApiModels.Binary(
                        ics, "text/calendar;charset=UTF-8", "attachment; filename=\"TECH1.ics\""));

        mvc.perform(get("/events/1")).andExpect(content().string(containsString("/events/1/calendar.ics")));
        mvc.perform(get("/events/1/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "attachment; filename=\"TECH1.ics\""))
                .andExpect(content().bytes(ics));
    }

    // ---- live search -------------------------------------------------------------------------------------------

    @Test
    void typingAsksForTheResultsFragmentOnly() throws Exception {
        String body = mvc.perform(get("/events").param("q", "quiz").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("id=\"results\"").contains("Tech Quiz").doesNotContain("<html");
    }

    @Test
    void theFiltersAreSentToTheCatalogue() throws Exception {
        mvc.perform(get("/events")
                        .param("q", " quiz ")
                        .param("category", "TECHNICAL")
                        .param("department", "CSE")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30")
                        .param("page", "1"))
                .andExpect(status().isOk());

        ArgumentCaptor<EventQuery> query = ArgumentCaptor.forClass(EventQuery.class);
        verify(gateway).search(query.capture());
        assertThat(query.getValue())
                .isEqualTo(new EventQuery(
                        " quiz ", "TECHNICAL", "CSE", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), false, 1));
    }

    /** Found in the first Playwright run: Explore opened on last week's completed and cancelled events. */
    @Test
    void exploreShowsUpcomingEventsUnlessPastOnesAreAskedFor() throws Exception {
        mvc.perform(get("/events")).andExpect(status().isOk());
        ArgumentCaptor<EventQuery> sent = ArgumentCaptor.forClass(EventQuery.class);
        verify(gateway).search(sent.capture());
        assertThat(sent.getValue().from()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));

        org.mockito.Mockito.clearInvocations(gateway);
        mvc.perform(get("/events").param("past", "true"))
                .andExpect(content().string(containsString("name=\"past\" value=\"true\" checked")));
        verify(gateway).search(sent.capture());
        assertThat(sent.getValue().from()).isNull();
    }

    @Test
    void theBackButtonGetsTheWholePageAndThePageWorksWithoutJavaScript() throws Exception {
        mvc.perform(get("/events").header("HX-Request", "true").header("HX-History-Restore-Request", "true"))
                .andExpect(content().string(containsString("<html")));
        mvc.perform(get("/events"))
                .andExpect(content().string(containsString("<noscript><button type=\"submit\"")))
                .andExpect(content().string(containsString("hx-push-url=\"true\"")))
                .andExpect(content().string(containsString("input delay:300ms, submit")));
    }

    @Test
    void pagingKeepsTheFiltersButLeavesEmptyOnesOut() throws Exception {
        given(gateway.search(any())).willReturn(new Page<>(List.of(PortalPagesTest.summary()), 1, 12, 30, 3));
        mvc.perform(get("/events").param("q", "quiz").param("category", "").param("page", "1"))
                .andExpect(content().string(containsString("/events?q=quiz&amp;page=0")))
                .andExpect(content().string(containsString("/events?q=quiz&amp;page=2")))
                .andExpect(content().string(not(containsString("category=&amp;"))))
                .andExpect(content().string(containsString("Page 2 of 3")));
    }

    // ---- registering and "You're in" --------------------------------------------------------------------------

    @Test
    void aConfirmedRegistrationGoesToYoureIn() throws Exception {
        given(gateway.register(1L, "key-1"))
                .willReturn(new RegisterResult(PortalPagesTest.registration("CONFIRMED", null, null), false));

        mvc.perform(post("/events/1/register")
                        .with(authentication(TestTokens.login(student)))
                        .with(csrf())
                        .param("idempotencyKey", "key-1"))
                .andExpect(redirectedUrl("/events/1/registered"));
    }

    @Test
    void aWaitlistedRegistrationStaysOnTheEventWithItsPosition() throws Exception {
        given(gateway.register(eq(1L), anyString()))
                .willReturn(new RegisterResult(PortalPagesTest.registration("WAITLISTED", 4, null), false));

        mvc.perform(post("/events/1/register")
                        .with(authentication(TestTokens.login(student)))
                        .with(csrf())
                        .param("idempotencyKey", "key-2"))
                .andExpect(redirectedUrl("/events/1"))
                .andExpect(flash().attribute("message", containsString("position 4")));
    }

    @Test
    void youreInPollsForThePassUntilItExists() throws Exception {
        confirmed();

        mvc.perform(get("/events/1/registered").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You&#39;re in")))
                .andExpect(content().string(containsString("Issuing your pass")))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")));

        mvc.perform(get("/events/1/registered/pass")
                        .with(authentication(TestTokens.login(student)))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")));

        given(gateway.myPasses()).willReturn(new Page<>(List.of(pass()), 0, 50, 1, 1));
        mvc.perform(get("/events/1/registered/pass")
                        .with(authentication(TestTokens.login(student)))
                        .header("HX-Request", "true"))
                .andExpect(status().is(286))
                .andExpect(content().string(containsString("Pass issued and signed")))
                .andExpect(content().string(containsString("/my/passes/" + PASS_ID)))
                .andExpect(content().string(not(containsString("hx-trigger"))));
    }

    @Test
    void thePollGivesUpPolitelyWhenThePassIsSlow() throws Exception {
        Registration old = new Registration(
                PortalPagesTest.REG_ID,
                1L,
                "MINIQUIZ26",
                "Tech Quiz",
                PortalPagesTest.START,
                "CONFIRMED",
                null,
                false,
                Instant.now().minusSeconds(120),
                null,
                "Priya Raman",
                "p@x");
        given(gateway.eligibility(1L)).willReturn(new Eligibility(true, true, List.of(), 0, 0L, old));

        mvc.perform(get("/events/1/registered/pass").with(authentication(TestTokens.login(student))))
                .andExpect(status().is(286))
                .andExpect(content().string(containsString("taking longer than usual")));
    }

    @Test
    void youreInIsOnlyForAConfirmedRegistration() throws Exception {
        given(gateway.eligibility(1L))
                .willReturn(new Eligibility(
                        true, true, List.of(), 0, 1L, PortalPagesTest.registration("WAITLISTED", 1, null)));

        mvc.perform(get("/events/1/registered").with(authentication(TestTokens.login(student))))
                .andExpect(redirectedUrl("/events/1"));
    }

    // ---- waitlist refresh --------------------------------------------------------------------------------------

    @Test
    void aWaitlistedBoxKeepsAskingAndStopsOnceConfirmed() throws Exception {
        given(gateway.eligibility(1L))
                .willReturn(new Eligibility(
                        true, true, List.of(), 0, 1L, PortalPagesTest.registration("WAITLISTED", 2, null)));
        mvc.perform(get("/events/1/my-registration").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-trigger=\"every 15s\"")))
                .andExpect(content().string(containsString("#2")));

        confirmed();
        mvc.perform(get("/events/1/my-registration").with(authentication(TestTokens.login(student))))
                .andExpect(status().is(286))
                .andExpect(content().string(containsString("You&#39;re registered")))
                .andExpect(content().string(not(containsString("hx-trigger"))));
    }

    @Test
    void myRegistrationsStopRefreshingWhenNothingIsWaiting() throws Exception {
        given(gateway.myRegistrations())
                .willReturn(new Page<>(List.of(PortalPagesTest.registration("WAITLISTED", 1, null)), 0, 50, 1, 1));
        mvc.perform(get("/my/registrations")
                        .with(authentication(TestTokens.login(student)))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("every 15s")));

        given(gateway.myRegistrations())
                .willReturn(new Page<>(List.of(PortalPagesTest.registration("CONFIRMED", null, null)), 0, 50, 1, 1));
        mvc.perform(get("/my/registrations")
                        .with(authentication(TestTokens.login(student)))
                        .header("HX-Request", "true"))
                .andExpect(status().is(286));
    }

    @Test
    void cancellingAsksInADialogAndReturnsWhereItCameFromButNeverOffSite() throws Exception {
        given(gateway.cancel(PortalPagesTest.REG_ID)).willReturn(PortalPagesTest.registration("CANCELLED", null, null));
        given(gateway.myRegistrations())
                .willReturn(new Page<>(List.of(PortalPagesTest.registration("CONFIRMED", null, null)), 0, 50, 1, 1));

        mvc.perform(get("/my/registrations").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("data-open-dialog=\"cancel-" + PortalPagesTest.REG_ID)))
                .andExpect(content().string(containsString("<dialog class=\"confirm\"")));
        mvc.perform(post("/my/registrations/{id}/cancel", PortalPagesTest.REG_ID)
                        .with(authentication(TestTokens.login(student)))
                        .with(csrf())
                        .param("back", "/events/1"))
                .andExpect(redirectedUrl("/events/1"));
        mvc.perform(post("/my/registrations/{id}/cancel", PortalPagesTest.REG_ID)
                        .with(authentication(TestTokens.login(student)))
                        .with(csrf())
                        .param("back", "//evil.example"))
                .andExpect(redirectedUrl("/"));
    }

    private void confirmed() {
        given(gateway.eligibility(1L))
                .willReturn(new Eligibility(
                        true, true, List.of(), 0, 0L, PortalPagesTest.registration("CONFIRMED", null, null)));
    }

    static Pass pass() {
        return new Pass(
                PASS_ID,
                "MINIQUIZ26-000001-3",
                "ISSUED",
                PortalPagesTest.REG_ID,
                1L,
                "MINIQUIZ26",
                "Tech Quiz",
                PortalPagesTest.START,
                PortalPagesTest.START.plusSeconds(7200),
                "Priya Raman",
                Instant.now(),
                PortalPagesTest.START.minusSeconds(9000),
                null,
                null,
                null,
                null,
                null);
    }
}
