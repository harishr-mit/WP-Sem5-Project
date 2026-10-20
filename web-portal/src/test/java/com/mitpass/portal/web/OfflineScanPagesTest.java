package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The offline scanner's server side (P7): the bundle, the sync pass-through, a fresh CSRF token, the worker. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfflineScanPagesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser volunteer = TestTokens.user("STUDENT", "VOLUNTEER");
    final PortalUser student = TestTokens.user("STUDENT");

    @BeforeEach
    void assignments() {
        given(gateway.myAssignments())
                .willReturn(List.of(new Assignment(
                        1L, "TECHFEST26", "TechFest Hackathon", Instant.now(), new Gate(11L, "MAIN", "Main Gate"))));
    }

    @Test
    void theScannerKnowsEachGatesEventAndLoadsTheOfflineCode() throws Exception {
        mvc.perform(get("/scan").with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-event=\"1\"")))
                .andExpect(content().string(containsString("data-scan-timeout-ms=\"4000\"")))
                .andExpect(content().string(containsString("Offline · {0} queued")))
                .andExpect(content().string(containsString("data-code=\"DUPLICATE_OFFLINE\"")))
                .andExpect(content().string(containsString("/js/offline")))
                .andExpect(content().string(containsString("id=\"sync-list\"")));
    }

    @Test
    void theBundleCombinesThePassKeysAndTheManifest() throws Exception {
        given(gateway.passJwks()).willReturn("{\"keys\":[{\"kty\":\"EC\",\"kid\":\"k1\"}]}");
        given(gateway.scanManifest(1L)).willReturn("{\"eventId\":1,\"passes\":[]}");

        mvc.perform(get("/scan/offline/bundle").param("eventId", "1").with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1))
                .andExpect(jsonPath("$.jwks.keys[0].kid").value("k1"))
                .andExpect(jsonPath("$.manifest.passes").isArray())
                .andExpect(content().string(not(containsString(TestTokens.ACCESS))));
        mvc.perform(get("/scan/offline/bundle").param("eventId", "1").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSyncIsPassedThroughAndNeedsCsrf() throws Exception {
        String body = "{\"deviceId\":\"web-1\",\"scans\":[]}";
        given(gateway.scanBatch(body)).willReturn("{\"results\":[]}");

        mvc.perform(post("/scan/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(post("/scan/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestTokens.login(volunteer)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
        verify(gateway).scanBatch(body);
    }

    @Test
    void aFreshCsrfTokenForTheSyncAndTheLoginPageWithoutASession() throws Exception {
        mvc.perform(get("/scan/csrf").with(authentication(TestTokens.login(volunteer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/scan/csrf")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theServiceWorkerIsServedToAnyone() throws Exception {
        mvc.perform(get("/scan-sw.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mitpass-scan-v1")));
    }
}
