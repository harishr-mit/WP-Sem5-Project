package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.client.ApiModels.MyAttendance;
import com.mitpass.portal.client.ApiModels.UserProfile;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The wallet's Stamps and Profile tabs (plan Q5; T08.5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyAccountPagesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    @Test
    void stampsShowEveryAdmissionAndCertificateProgress() throws Exception {
        given(gateway.myAttendance())
                .willReturn(List.of(
                        new MyAttendance(
                                1L,
                                "RANG26",
                                "Rangoli Night",
                                "RANG26-000003-1",
                                1,
                                1,
                                1,
                                true,
                                Instant.parse("2026-10-10T13:00:00Z")),
                        new MyAttendance(
                                2L,
                                "AIWS26",
                                "AI Workshop",
                                "AIWS26-000007-4",
                                1,
                                3,
                                2,
                                false,
                                Instant.parse("2026-10-12T04:00:00Z")),
                        new MyAttendance(3L, "QUIZ26", "Tech Quiz", "QUIZ26-000001-9", 0, 1, 1, false, null)));

        mvc.perform(get("/my/stamps").with(authentication(TestTokens.login(TestTokens.user("STUDENT")))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 stamps")))
                .andExpect(content().string(containsString("Rangoli Night")))
                .andExpect(content().string(containsString("1 / 2")))
                .andExpect(content().string(containsString("Attend 1 more session to earn it.")));
    }

    @Test
    void noStampsYetSaysHowToGetOne() throws Exception {
        given(gateway.myAttendance()).willReturn(List.of());

        mvc.perform(get("/my/stamps").with(authentication(TestTokens.login(TestTokens.user("STUDENT")))))
                .andExpect(content().string(containsString("Nothing stamped yet")));
    }

    @Test
    void theProfileShowsTheAccount() throws Exception {
        given(gateway.me()).willReturn(profile());

        mvc.perform(get("/my/profile").with(authentication(TestTokens.login(TestTokens.user("STUDENT")))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(containsString("2026509001")))
                .andExpect(content().string(containsString(">Volunteer<")))
                .andExpect(content().string(containsString("data-open-dialog=\"logout-everywhere\"")));
    }

    @Test
    void loggingOutEverywhereEndsTheBackendLoginsAndThisSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/my/profile/logout-everywhere")
                        .session(session)
                        .with(authentication(TestTokens.login(TestTokens.user("STUDENT"))))
                        .with(csrf()))
                .andExpect(redirectedUrl("/login?loggedOut"));

        verify(gateway).logoutEverywhere();
        assertThat(session.isInvalid()).isTrue();
    }

    static UserProfile profile() {
        return new UserProfile(
                UUID.randomUUID(),
                "2026509001@student.annauniv.edu",
                "Priya Raman",
                "CSE",
                3,
                "2026509001",
                List.of("STUDENT", "VOLUNTEER"),
                true,
                false,
                Instant.parse("2026-10-09T10:00:00Z"),
                null);
    }
}
