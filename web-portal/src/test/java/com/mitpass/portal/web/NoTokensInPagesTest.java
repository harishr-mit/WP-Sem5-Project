package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.AttendanceSummary;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.InstanceHealth;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Venue;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The BFF rule (PROJECT.md §3, D9, exit criterion 8): no rendered page contains the session's access token, its
 * refresh token, or anything shaped like a JWT. One user with every role visits every GET page of the portal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoTokensInPagesTest {

    static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000005");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser everyone = new PortalUser(
            USER_ID,
            "all@mitpass.test",
            "All Roles",
            Set.of("STUDENT", "ORGANIZER", "VOLUNTEER", "ADMIN"),
            "CSE",
            3,
            TestTokens.tokenResponse(USER_ID, "All Roles", List.of("ADMIN"), "CSE", 3)
                    .accessToken(),
            TestTokens.REFRESH,
            Instant.now().plusSeconds(900));

    @BeforeEach
    void backend() {
        var event = OrganizerFormsTest.withSessions();
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.departments()).willReturn(List.of(new Department("CSE", "Computer Science")));
        given(gateway.venues()).willReturn(List.of(new Venue(3L, "Main Auditorium", null)));
        given(gateway.search(any())).willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 12, 1, 1));
        given(gateway.events(any(), any(), any(), anyInt()))
                .willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 20, 1, 1));
        given(gateway.event(anyLong())).willReturn(event);
        given(gateway.eligibility(anyLong()))
                .willReturn(new Eligibility(
                        true, true, List.of(), 3, 0L, PortalPagesTest.registration("CONFIRMED", null, null)));
        given(gateway.myRegistrations())
                .willReturn(new Page<>(List.of(PortalPagesTest.registration("CONFIRMED", null, null)), 0, 50, 1, 1));
        given(gateway.myPasses()).willReturn(new Page<>(List.of(EventPagesTest.pass()), 0, 50, 1, 1));
        given(gateway.pass(EventPagesTest.PASS_ID)).willReturn(EventPagesTest.pass());
        given(gateway.myAttendance()).willReturn(List.of());
        given(gateway.me()).willReturn(MyAccountPagesTest.profile());
        given(gateway.eventRegistrations(anyLong()))
                .willReturn(new Page<>(List.of(PortalPagesTest.registration("CONFIRMED", null, null)), 0, 100, 1, 1));
        given(gateway.eventPasses(anyLong())).willReturn(new Page<>(List.of(EventPagesTest.pass()), 0, 100, 1, 1));
        given(gateway.gates(anyLong())).willReturn(List.of(new Gate(11L, "MAIN", "Main Gate")));
        given(gateway.eventVolunteers(anyLong())).willReturn(List.of());
        given(gateway.attendanceSummary(anyLong()))
                .willReturn(new AttendanceSummary(
                        9L, "AIWS26", "AI Workshop", 1L, 0L, 0.0, 0L, List.of(), List.of(), null));
        given(gateway.attendees(anyLong())).willReturn(new Page<>(List.of(), 0, 100, 0, 0));
        given(gateway.certificates(anyLong())).willReturn(List.of());
        given(gateway.myAssignments()).willReturn(List.of());
        given(gateway.users(any(), any(), anyInt()))
                .willReturn(new Page<>(List.of(MyAccountPagesTest.profile()), 0, 20, 1, 1));
        given(gateway.user(any())).willReturn(MyAccountPagesTest.profile());
        given(gateway.adminHealth())
                .willReturn(List.of(new InstanceHealth(
                        "auth-service", "a", "http://h:8081", "UP", Map.of("db", "UP"), null, Instant.now())));
    }

    @Test
    void noPageCarriesATokenOfTheSession() throws Exception {
        List<String> pages = List.of(
                "/events",
                "/events/9",
                "/events/9/registered",
                "/my/registrations",
                "/my/passes",
                "/my/passes/" + EventPagesTest.PASS_ID,
                "/my/stamps",
                "/my/profile",
                "/organizer/events",
                "/organizer/events/new",
                "/organizer/events/9/edit",
                "/organizer/events/9/registrations",
                "/organizer/events/9/staff",
                "/organizer/events/9/attendance",
                "/scan",
                "/admin/users",
                "/admin/users/" + USER_ID,
                "/admin/health",
                "/admin/keys",
                "/login",
                "/signup",
                "/check-email");
        for (String page : pages) {
            String html = mvc.perform(get(page).with(authentication(TestTokens.login(everyone))))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(html).as(page).isNotBlank();
            assertThat(html).as(page).doesNotContain(everyone.accessToken()).doesNotContain(TestTokens.REFRESH);
            assertThat(JWT.matcher(html).find())
                    .as(page + " contains something JWT-shaped")
                    .isFalse();
        }
    }
}
