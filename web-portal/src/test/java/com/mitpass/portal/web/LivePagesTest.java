package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.LiveBucket;
import com.mitpass.portal.client.ApiModels.LiveGate;
import com.mitpass.portal.client.ApiModels.LiveScan;
import com.mitpass.portal.client.ApiModels.LiveSnapshot;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The live dashboard page (screen 7, P7): first paint, the JSON for chart and polling, and who may open the feed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LivePagesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser organizer = TestTokens.user("ORGANIZER");
    final PortalUser student = TestTokens.user("STUDENT");

    static LiveSnapshot snapshot() {
        Instant at = Instant.parse("2026-10-24T04:00:00Z");
        return new LiveSnapshot(
                9,
                at,
                600,
                513,
                487,
                650,
                List.of(new LiveGate("MAIN", 300), new LiveGate("NORTH", 213)),
                List.of(new LiveBucket(at, 42)),
                List.of(
                        new LiveScan(at, "Priya", "MAIN", "ACCEPTED", "GREEN"),
                        new LiveScan(at, null, "NORTH", "REVOKED", "RED")));
    }

    @BeforeEach
    void stubs() {
        given(gateway.event(9L)).willReturn(PortalPagesTest.detail(9, List.of(), "LIVE", List.of()));
        given(gateway.liveSnapshot(9L)).willReturn(snapshot());
    }

    @Test
    void theFirstPaintShowsTheSnapshotAndLoadsTheFeed() throws Exception {
        mvc.perform(get("/organizer/events/9/live").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-live-event=\"9\"")))
                .andExpect(content().string(containsString(">513<")))
                .andExpect(content().string(containsString("aria-valuenow=\"74\""))) // 487 of 650
                .andExpect(content().string(containsString("Priya")))
                .andExpect(content().string(containsString("Someone")))
                .andExpect(content().string(containsString("Revoked")))
                .andExpect(content().string(containsString("data-code=\"DUPLICATE_OFFLINE\"")))
                .andExpect(content().string(containsString("/webjars/stomp__stompjs/")))
                .andExpect(content().string(containsString("/webjars/chart.js/")))
                .andExpect(content().string(containsString("/js/dashboard")));
    }

    @Test
    void theSnapshotAsJsonIsNotCached() throws Exception {
        mvc.perform(get("/organizer/events/9/live.json").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedIn").value(513))
                .andExpect(jsonPath("$.gates[1].code").value("NORTH"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"));
    }

    @Test
    void onlyOrganizersAndAdminsReachThePageAndTheFeed() throws Exception {
        mvc.perform(get("/organizer/events/9/live").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/portal-ws").with(authentication(TestTokens.login(student))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/portal-ws")).andExpect(status().is3xxRedirection()); // to the login page
    }
}
