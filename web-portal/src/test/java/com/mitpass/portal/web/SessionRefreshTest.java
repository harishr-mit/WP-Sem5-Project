package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.client.RefreshClient;
import com.mitpass.portal.client.SessionExpiredException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/**
 * Automatic refresh in a real session (docs/plans/phase-6.md §7; D9 row "log in again after 15 min" replaced). The
 * login lives in the session as Spring Security stores it, so the filter, the refresher and the save back to the
 * session all run as in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionRefreshTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    @MockitoBean
    RefreshClient refreshClient;

    @BeforeEach
    void pages() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.search(any())).willReturn(new Page<>(List.of(), 0, 12, 0, 0));
        given(gateway.myRegistrations()).willReturn(new Page<>(List.of(), 0, 50, 0, 0));
    }

    static MockHttpSession sessionOf(PortalUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(TestTokens.login(user)));
        return session;
    }

    static PortalUser userIn(MockHttpSession session) {
        SecurityContext context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (PortalUser) context.getAuthentication().getPrincipal();
    }

    @Test
    void anExpiredTokenIsRefreshedAndThePageJustWorks() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().minusSeconds(5), "STUDENT"));
        given(refreshClient.refresh(TestTokens.REFRESH))
                .willReturn(TestTokens.tokenResponse(UUID.randomUUID(), "Priya Raman", List.of("STUDENT"), "CSE", 1));

        mvc.perform(get("/my/registrations").session(session)).andExpect(status().isOk());

        PortalUser now = userIn(session);
        assertThat(now.accessToken()).isNotEqualTo(TestTokens.ACCESS);
        assertThat(now.expiresAt()).isAfter(Instant.now().plusSeconds(600));
        verify(gateway).myRegistrations();
    }

    @Test
    void aTokenAboutToExpireIsRefreshedAheadOfTime() throws Exception {
        // 30 s left, refresh-ahead is 60 s
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().plusSeconds(30), "STUDENT"));
        given(refreshClient.refresh(TestTokens.REFRESH))
                .willReturn(TestTokens.tokenResponse(UUID.randomUUID(), "Priya Raman", List.of("STUDENT"), "CSE", 1));

        mvc.perform(get("/my/registrations").session(session)).andExpect(status().isOk());

        assertThat(userIn(session).accessToken()).isNotEqualTo(TestTokens.ACCESS);
    }

    @Test
    void aFreshTokenIsLeftAlone() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().plusSeconds(600), "STUDENT"));

        mvc.perform(get("/my/registrations").session(session)).andExpect(status().isOk());

        verify(refreshClient, never()).refresh(anyString());
        assertThat(userIn(session).accessToken()).isEqualTo(TestTokens.ACCESS);
    }

    @Test
    void aRefusedRefreshEndsTheSession() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().minusSeconds(5), "STUDENT"));
        given(refreshClient.refresh(anyString())).willThrow(new SessionExpiredException());

        mvc.perform(get("/my/registrations").session(session)).andExpect(redirectedUrl("/login?expired"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anHtmxRequestIsToldToGoToTheLoginPageInsteadOfFollowingARedirect() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().minusSeconds(5), "STUDENT"));
        given(refreshClient.refresh(anyString())).willThrow(new SessionExpiredException());

        mvc.perform(get("/my/registrations").session(session).header("HX-Request", "true"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Redirect", "/login?expired"));

        mvc.perform(get("/my/registrations").header("HX-Request", "true"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Redirect", "/login"));
    }

    @Test
    void whenAuthServiceIsUnreachablePublicPagesStillWork() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().minusSeconds(5), "STUDENT"));
        given(refreshClient.refresh(anyString())).willThrow(new ResourceAccessException("Connection refused"));

        mvc.perform(get("/events").session(session)).andExpect(status().isOk());
    }

    @Test
    void aTokenRejectedByTheGatewayEndsTheSession() throws Exception {
        MockHttpSession session = sessionOf(TestTokens.user(Instant.now().plusSeconds(600), "STUDENT"));
        given(gateway.myRegistrations()).willThrow(new SessionExpiredException());

        mvc.perform(get("/my/registrations").session(session)).andExpect(redirectedUrl("/login?expired"));
    }
}
