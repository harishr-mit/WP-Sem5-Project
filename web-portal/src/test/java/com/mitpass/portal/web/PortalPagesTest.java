package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.client.SessionExpiredException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The P3 portal slice (docs/plans/phase-3.md §10) with the gateway mocked: pages, PRG, CSRF, roles, BFF rule. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalPagesTest {

    static final Instant START = Instant.parse("2026-11-28T09:30:00Z");
    static final UUID REG_ID = UUID.fromString("00000000-0000-4000-8000-0000000000r1".replace("r", "a"));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser student = TestTokens.user("STUDENT");
    final PortalUser organizer = TestTokens.user("ORGANIZER");

    @BeforeEach
    void catalogue() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.events(any(), any(), any(), anyInt())).willReturn(new Page<>(List.of(summary()), 0, 20, 1, 1));
        given(gateway.event(1L))
                .willReturn(detail(1, List.of(), "REGISTRATION_OPEN", List.of("REGISTRATION_CLOSED", "CANCELLED")));
        given(gateway.departments()).willReturn(List.of(new Department("CSE", "Computer Science")));
    }

    // ---- anonymous -------------------------------------------------------------------------------------------------

    @Test
    void anyoneCanBrowseTheCatalogueAndAnEvent() throws Exception {
        mvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tech Quiz")))
                .andExpect(content().string(containsString("0 / 1")));
        mvc.perform(get("/events/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("to register")))
                .andExpect(content().string(containsString("Open to everyone")));
        verify(gateway, never()).eligibility(anyLong());
    }

    @Test
    void privatePagesSendAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/my/registrations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/organizer/events")).andExpect(status().is3xxRedirection());
    }

    // ---- login / logout ----------------------------------------------------------------------------------------

    @Test
    void loginGoesThroughAuthServiceAndKeepsTheTokensServerSide() throws Exception {
        UUID id = UUID.randomUUID();
        given(gateway.login("priya@student.test", "Passw0rd!"))
                .willReturn(TestTokens.tokenResponse(id, "Priya Raman", List.of("STUDENT"), "CSE", 1));

        mvc.perform(post("/login")
                        .with(csrf())
                        .param("email", "priya@student.test")
                        .param("password", "Passw0rd!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events"))
                .andExpect(authenticated().withRoles("STUDENT"));
    }

    @Test
    void unverifiedEmailIsExplainedOnTheLoginPage() throws Exception {
        given(gateway.login(anyString(), anyString()))
                .willThrow(new ApiProblemException(403, "urn:mitpass:problem:email-not-verified", "x", null, null));

        var session = mvc.perform(
                        post("/login").with(csrf()).param("email", "a@b.c").param("password", "p"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated())
                .andReturn()
                .getRequest()
                .getSession();
        mvc.perform(get("/login").param("error", "").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(content().string(containsString("verify your email")));
    }

    @Test
    void loginWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/login").param("email", "a@b.c").param("password", "p"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        mvc.perform(post("/logout").with(csrf()).with(authentication(TestTokens.login(student))))
                .andExpect(redirectedUrl("/login?loggedOut"));
        verify(gateway).logout(TestTokens.REFRESH);
    }

    // ---- sign-up ---------------------------------------------------------------------------------------------------

    @Test
    void signupFormListsDepartments() throws Exception {
        mvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Computer Science")));
    }

    @Test
    void invalidSignupIsShownInlineWithoutCallingTheBackend() throws Exception {
        mvc.perform(post("/signup")
                        .with(csrf())
                        .param("fullName", "")
                        .param("email", "nope")
                        .param("password", "short"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "fullName", "email", "password", "department"));
        verify(gateway, never()).signup(any(), any(), any(), any(), any(), any());
    }

    @Test
    void backendFieldErrorsLandOnTheSameFields() throws Exception {
        org.mockito.Mockito.doThrow(new ApiProblemException(
                        400,
                        "urn:mitpass:problem:validation",
                        "1 field is invalid",
                        List.of(new FieldError("email", "must be a @student.annauniv.edu or @gmail.com address")),
                        null))
                .when(gateway)
                .signup(any(), any(), any(), any(), any(), any());

        mvc.perform(validSignup())
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "email"))
                .andExpect(content().string(containsString("@gmail.com address")));
    }

    @Test
    void successfulSignupRedirectsToLoginWithTheInboxLink() throws Exception {
        mvc.perform(validSignup())
                .andExpect(redirectedUrl("/login?signedUp"))
                .andExpect(flash().attribute("signedUpEmail", "2026509032@student.annauniv.edu"))
                .andExpect(flash().attribute("mailpitUrl", "http://mailpit.test"));
    }

    // ---- event detail and registering ---------------------------------------------------------------------------

    @Test
    void eligibleStudentSeesARegisterButtonWithAFreshIdempotencyKey() throws Exception {
        given(gateway.eligibility(1L)).willReturn(new Eligibility(true, true, List.of(), 1, 0L, null));

        mvc.perform(get("/events/1").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("eligible: CSE · year 1")))
                .andExpect(content().string(containsString("name=\"idempotencyKey\"")))
                .andExpect(content().string(containsString(">Register<")));
    }

    @Test
    void aFullEventOffersTheWaitlist() throws Exception {
        given(gateway.eligibility(1L)).willReturn(new Eligibility(true, true, List.of(), 0, 2L, null));

        mvc.perform(get("/events/1").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("join the waitlist (2 waiting)")))
                .andExpect(content().string(containsString("Join the waitlist")));
    }

    @Test
    void ineligibleStudentSeesTheReasons() throws Exception {
        given(gateway.eligibility(1L))
                .willReturn(
                        new Eligibility(false, true, List.of("Open to CSE, IT only (you are in AERO)"), 1, 0L, null));

        mvc.perform(get("/events/1").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("you are in AERO")))
                .andExpect(content().string(not(containsString("name=\"idempotencyKey\""))));
    }

    @Test
    void registeredStudentSeesTheirStatusAndACancelButton() throws Exception {
        given(gateway.eligibility(1L))
                .willReturn(new Eligibility(true, true, List.of(), 0, 1L, registration("WAITLISTED", 1, null)));

        mvc.perform(get("/events/1").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("WAITLISTED #1")))
                .andExpect(content().string(containsString("Cancel my registration")));
    }

    @Test
    void registerIsPostRedirectGetWithAFlashMessage() throws Exception {
        given(gateway.register(1L, "key-1")).willReturn(new RegisterResult(registration("WAITLISTED", 4, null), false));

        mvc.perform(post("/events/1/register")
                        .with(csrf())
                        .with(authentication(TestTokens.login(student)))
                        .param("idempotencyKey", "key-1"))
                .andExpect(redirectedUrl("/events/1"))
                .andExpect(flash().attribute("message", containsString("waitlist at position 4")));
    }

    @Test
    void aRefusedRegistrationIsAFlashError() throws Exception {
        given(gateway.register(eq(1L), anyString()))
                .willThrow(new ApiProblemException(
                        422,
                        "urn:mitpass:problem:not-eligible",
                        "x",
                        null,
                        List.of("Open to CSE only (you are in AERO)")));

        mvc.perform(post("/events/1/register")
                        .with(csrf())
                        .with(authentication(TestTokens.login(student)))
                        .param("idempotencyKey", "k"))
                .andExpect(redirectedUrl("/events/1"))
                .andExpect(flash().attribute("error", containsString("you are in AERO")));
    }

    @Test
    void formPostsWithoutCsrfAreRefused() throws Exception {
        mvc.perform(post("/events/1/register")
                        .with(authentication(TestTokens.login(student)))
                        .param("idempotencyKey", "k"))
                .andExpect(status().isForbidden());
        verify(gateway, never()).register(anyLong(), any());
    }

    // ---- my registrations ------------------------------------------------------------------------------------------

    @Test
    void myRegistrationsShowsStatusAndPromotion() throws Exception {
        given(gateway.myRegistrations())
                .willReturn(new Page<>(
                        List.of(registration("CONFIRMED", null, null), registration("CANCELLED", null, null)),
                        0,
                        50,
                        2,
                        1));

        mvc.perform(get("/my/registrations").with(authentication(TestTokens.login(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("CANCELLED")));
    }

    @Test
    void cancellingSaysWhenTheSeatWentToTheWaitlist() throws Exception {
        given(gateway.cancel(REG_ID)).willReturn(registration("CANCELLED", null, UUID.randomUUID()));

        mvc.perform(post("/my/registrations/{id}/cancel", REG_ID)
                        .with(csrf())
                        .with(authentication(TestTokens.login(student))))
                .andExpect(redirectedUrl("/my/registrations"))
                .andExpect(flash().attribute("message", containsString("next student on the waitlist")));
    }

    // ---- organizer -------------------------------------------------------------------------------------------------

    @Test
    void organizerSeesOnlyTheAllowedMoves() throws Exception {
        given(gateway.event(1L)).willReturn(detail(1, List.of(), "REGISTRATION_CLOSED", List.of("CANCELLED")));

        mvc.perform(get("/organizer/events").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"CANCELLED\"")))
                .andExpect(content().string(not(containsString("value=\"LIVE\""))));
        verify(gateway).events(isNull(), isNull(), eq(organizer.id()), eq(0));
    }

    @Test
    void organizerMovesAnEvent() throws Exception {
        given(gateway.transition(1L, "REGISTRATION_CLOSED"))
                .willReturn(detail(1, List.of(), "REGISTRATION_CLOSED", List.of("CANCELLED")));

        mvc.perform(post("/organizer/events/1/transitions")
                        .with(csrf())
                        .with(authentication(TestTokens.login(organizer)))
                        .param("to", "REGISTRATION_CLOSED"))
                .andExpect(redirectedUrl("/organizer/events"))
                .andExpect(flash().attribute("message", "Tech Quiz is now REGISTRATION_CLOSED."));
    }

    @Test
    void studentsCannotOpenOrganizerPages() throws Exception {
        mvc.perform(get("/organizer/events").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
    }

    // ---- BFF rule and expiry --------------------------------------------------------------------------------------

    @Test
    void tokensNeverReachTheBrowser() throws Exception {
        given(gateway.eligibility(1L)).willReturn(new Eligibility(true, true, List.of(), 1, 0L, null));
        given(gateway.myRegistrations())
                .willReturn(new Page<>(List.of(registration("CONFIRMED", null, null)), 0, 50, 1, 1));

        for (String page : List.of("/events", "/events/1", "/my/registrations")) {
            mvc.perform(get(page).with(authentication(TestTokens.login(student))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString(TestTokens.ACCESS))))
                    .andExpect(content().string(not(containsString(TestTokens.REFRESH))));
        }
    }

    @Test
    void anExpiredTokenEndsTheSession() throws Exception {
        PortalUser expired = TestTokens.user(Instant.now().minusSeconds(1), "STUDENT");

        mvc.perform(get("/my/registrations").with(authentication(TestTokens.login(expired))))
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void aTokenRejectedByTheGatewayAlsoEndsTheSession() throws Exception {
        given(gateway.myRegistrations()).willThrow(new SessionExpiredException());

        mvc.perform(get("/my/registrations").with(authentication(TestTokens.login(student))))
                .andExpect(redirectedUrl("/login?expired"));
    }

    // ---- fixtures
    // ----------------------------------------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validSignup() {
        return post("/signup")
                .with(csrf())
                .param("fullName", "Priya Raman")
                .param("email", "2026509032@student.annauniv.edu")
                .param("password", "Techfest2026")
                .param("department", "CSE")
                .param("year", "1");
    }

    static EventSummary summary() {
        return new EventSummary(
                1L,
                "MINIQUIZ26",
                "Tech Quiz",
                "Technical",
                "Library Seminar Hall",
                null,
                1,
                START,
                "REGISTRATION_OPEN",
                1,
                0);
    }

    static EventDetail detail(long id, List<String> departments, String status, List<String> allowed) {
        return new EventDetail(
                id,
                "MINIQUIZ26",
                "Tech Quiz",
                "One seat.",
                new Category(1L, "TECHNICAL", "Technical"),
                null,
                null,
                1,
                START,
                START.plusSeconds(7200),
                null,
                START.minusSeconds(86_400),
                status,
                UUID.randomUUID(),
                "Anitha Rao",
                departments,
                null,
                null,
                allowed,
                0,
                1);
    }

    static Registration registration(String status, Integer position, UUID promoted) {
        return new Registration(
                REG_ID,
                1L,
                "MINIQUIZ26",
                "Tech Quiz",
                START,
                status,
                position,
                false,
                START,
                promoted,
                "Priya Raman",
                "priya@student.test");
    }
}
