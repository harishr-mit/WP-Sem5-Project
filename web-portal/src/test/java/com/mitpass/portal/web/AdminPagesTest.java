package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.InstanceHealth;
import com.mitpass.portal.client.ApiModels.KeyRotation;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.UserProfile;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The admin pages (T08.10): users and roles, health overview, key rotation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPagesTest {

    static final UUID PRIYA = UUID.fromString("00000000-0000-4000-8000-000000000005");
    static final UUID KARTHIK = UUID.fromString("00000000-0000-4000-8000-000000000004");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser admin = new PortalUser(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "admin@mitpass.test",
            "Admin",
            Set.of("ADMIN"),
            null,
            null,
            TestTokens.ACCESS,
            TestTokens.REFRESH,
            Instant.now().plusSeconds(900));

    @BeforeEach
    void users() {
        given(gateway.user(KARTHIK))
                .willReturn(user(KARTHIK, "Karthik Selvam", List.of("STUDENT", "VOLUNTEER"), false));
        given(gateway.users(any(), any(), anyInt()))
                .willReturn(new Page<>(
                        List.of(user(KARTHIK, "Karthik Selvam", List.of("STUDENT", "VOLUNTEER"), true)), 0, 20, 1, 1));
    }

    @Test
    void onlyAdminsGetIn() throws Exception {
        mvc.perform(get("/admin/users").with(authentication(TestTokens.login(TestTokens.user("ORGANIZER")))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(authentication(TestTokens.login(admin))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Karthik Selvam")))
                .andExpect(content().string(containsString("Locked")));
    }

    @Test
    void theSearchIsSentAndHtmxGetsOnlyTheResults() throws Exception {
        mvc.perform(get("/admin/users")
                        .param("q", "karthik")
                        .param("role", "VOLUNTEER")
                        .header("HX-Request", "true")
                        .with(authentication(TestTokens.login(admin))))
                .andExpect(content().string(containsString("id=\"user-results\"")))
                .andExpect(content().string(not(containsString("<html"))));
        verify(gateway).users("karthik", "VOLUNTEER", 0);
    }

    @Test
    void grantingAdminAsksFirstAndOnlyThenSaves() throws Exception {
        mvc.perform(post("/admin/users/{id}/roles", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("roles", "STUDENT", "VOLUNTEER", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Make Karthik Selvam an admin?")));
        verify(gateway, never()).setRoles(any(), any());

        given(gateway.setRoles(eq(KARTHIK), any()))
                .willReturn(user(KARTHIK, "Karthik Selvam", List.of("ADMIN"), false));
        mvc.perform(post("/admin/users/{id}/roles", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("roles", "STUDENT", "VOLUNTEER", "ADMIN")
                        .param("confirmed", "true"))
                .andExpect(redirectedUrl("/admin/users/" + KARTHIK))
                .andExpect(flash().attribute("message", containsString("Karthik Selvam")));
        verify(gateway).setRoles(KARTHIK, new java.util.TreeSet<>(Set.of("ADMIN", "STUDENT", "VOLUNTEER")));
    }

    @Test
    void otherRoleChangesSaveAtOnceButNeverEmptyOrRemovingYourOwnAdmin() throws Exception {
        given(gateway.setRoles(eq(KARTHIK), any()))
                .willReturn(user(KARTHIK, "Karthik Selvam", List.of("ORGANIZER"), false));
        mvc.perform(post("/admin/users/{id}/roles", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("roles", "ORGANIZER", "BOSS"))
                .andExpect(redirectedUrl("/admin/users/" + KARTHIK));
        verify(gateway).setRoles(KARTHIK, new java.util.TreeSet<>(Set.of("ORGANIZER")));

        mvc.perform(post("/admin/users/{id}/roles", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(flash().attribute("error", "Keep at least one role."));

        given(gateway.user(admin.id())).willReturn(user(admin.id(), "Admin", List.of("ADMIN"), false));
        mvc.perform(post("/admin/users/{id}/roles", admin.id())
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("roles", "STUDENT"))
                .andExpect(flash().attribute("error", containsString("your own admin role")));
    }

    /** RL10: a ban is confirmed first; the page then shows it and offers to lift it. */
    @Test
    void banningNeedsConfirmedAndThePageShowsTheBan() throws Exception {
        UserProfile k = user(KARTHIK, "Karthik S", List.of("STUDENT"), false);
        UserProfile banned = new UserProfile(
                k.id(),
                k.email(),
                k.fullName(),
                k.department(),
                k.year(),
                k.registerNumber(),
                k.roles(),
                true,
                false,
                k.createdAt(),
                null,
                true);
        given(gateway.setBanned(KARTHIK, true)).willReturn(banned);

        mvc.perform(post("/admin/users/{id}/ban", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(flash().attribute("error", "Please confirm first."));
        verify(gateway, never()).setBanned(KARTHIK, true);
        mvc.perform(post("/admin/users/{id}/ban", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("confirmed", "true"))
                .andExpect(flash().attribute("message", "Karthik S is banned."));

        given(gateway.user(KARTHIK)).willReturn(banned);
        mvc.perform(get("/admin/users/{id}", KARTHIK).with(authentication(TestTokens.login(admin))))
                .andExpect(content().string(containsString(">Banned<")))
                .andExpect(content().string(containsString(">Lift ban<")))
                .andExpect(content().string(not(containsString(">Active<"))));
    }

    @Test
    void endingSessionsAndRotatingKeysNeedConfirmed() throws Exception {
        mvc.perform(post("/admin/users/{id}/sessions/end", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(flash().attribute("error", "Please confirm first."));
        verify(gateway, never()).endSessions(KARTHIK);
        mvc.perform(post("/admin/users/{id}/sessions/end", KARTHIK)
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("confirmed", "true"))
                .andExpect(flash().attribute("message", "All sessions ended."));
        verify(gateway).endSessions(KARTHIK);

        given(gateway.rotatePassKey()).willReturn(new KeyRotation("pass-2026-10-18"));
        mvc.perform(post("/admin/keys/pass/rotate")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/keys"));
        verify(gateway, never()).rotatePassKey();
        mvc.perform(post("/admin/keys/pass/rotate")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("confirmed", "true"))
                .andExpect(flash().attribute("message", containsString("pass-2026-10-18")));
    }

    @Test
    void healthShowsEveryInstanceAndRefreshesItself() throws Exception {
        given(gateway.adminHealth())
                .willReturn(List.of(
                        new InstanceHealth(
                                "auth-service", "a1", "http://h:8081", "UP", Map.of("db", "UP"), null, Instant.now()),
                        new InstanceHealth(
                                "pass-service",
                                "p1",
                                "http://h:8083",
                                "DOWN",
                                Map.of(),
                                "no answer in 2 s",
                                Instant.now())));

        mvc.perform(get("/admin/health").with(authentication(TestTokens.login(admin))))
                .andExpect(content().string(containsString("Something is down")))
                .andExpect(content().string(containsString("no answer in 2 s")))
                .andExpect(content().string(containsString("hx-trigger=\"every 10s\"")));
    }

    @Test
    void deadLettersOfEveryConsumerWithReplayAndAConfirmedDiscard() throws Exception {
        var letter = new com.mitpass.portal.client.ApiModels.DeadLetter(
                "notification-service.inbox", "m-1", "pass.issued", "pass.issued", 1, "rejected", Instant.now(), 120);
        given(gateway.deadLetters("notification-service"))
                .willReturn(new com.mitpass.portal.client.ApiModels.DeadLetterOverview(
                        List.of(new com.mitpass.portal.client.ApiModels.DeadLetterQueue(
                                "notification-service.inbox", "notification-service.inbox.dlq", 1)),
                        List.of(letter)));
        given(gateway.deadLetters("pass-service"))
                .willReturn(new com.mitpass.portal.client.ApiModels.DeadLetterOverview(List.of(), List.of()));
        given(gateway.deadLetters("checkin-service"))
                .willThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));

        mvc.perform(get("/admin/dead-letters").with(authentication(TestTokens.login(admin))))
                .andExpect(content().string(containsString("notification-service")))
                .andExpect(content().string(containsString("1 waiting")))
                .andExpect(content().string(containsString("/admin/dead-letters/notification-service/m-1/replay")))
                .andExpect(content().string(containsString("connection refused")))
                .andExpect(content().string(containsString("Nothing waiting.")));

        mvc.perform(post("/admin/dead-letters/notification-service/m-1/replay")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(flash().attribute("message", "Replayed 1 message(s)."));
        org.mockito.Mockito.verify(gateway).replayDeadLetter("notification-service", "m-1");

        mvc.perform(post("/admin/dead-letters/notification-service/m-1/discard")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(flash().attributeExists("error")); // not confirmed
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).discardDeadLetter(any(), any());
        mvc.perform(post("/admin/dead-letters/notification-service/m-1/discard")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf())
                        .param("confirmed", "true"))
                .andExpect(flash().attribute("message", "Discarded m-1."));

        mvc.perform(post("/admin/dead-letters/event-service/m-1/replay")
                        .with(authentication(TestTokens.login(admin)))
                        .with(csrf()))
                .andExpect(status().isNotFound()); // only the three consumers
        mvc.perform(get("/admin/dead-letters").with(authentication(TestTokens.login(TestTokens.user("ORGANIZER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDeadLetterWarningIsAmberNotDown() throws Exception {
        given(gateway.adminHealth())
                .willReturn(List.of(new InstanceHealth(
                        "pass-service",
                        "p1",
                        "http://h:8083",
                        "WARNING",
                        Map.of("db", "UP", "deadLetters", "WARNING"),
                        null,
                        Instant.now())));

        mvc.perform(get("/admin/health").with(authentication(TestTokens.login(admin))))
                .andExpect(content().string(containsString("flash--warn")))
                .andExpect(content().string(containsString("dead-letter queue")))
                .andExpect(content().string(containsString("dot--warn")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Something is down"))));
    }

    static UserProfile user(UUID id, String name, List<String> roles, boolean locked) {
        return new UserProfile(
                id,
                "k@mitpass.test",
                name,
                "CSE",
                3,
                null,
                roles,
                true,
                locked,
                Instant.parse("2026-10-09T10:00:00Z"),
                null);
    }
}
