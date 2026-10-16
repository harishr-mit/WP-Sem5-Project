package com.mitpass.event.web;

import static com.mitpass.event.web.LifecycleTests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-event check-in settings (docs/plans/phase-5.md §6, D14): re-entry and the certificate threshold. checkin-service
 * reads them from the event detail.
 */
@EventIntegrationTest
class CheckinSettingsTests {

    static final AtomicInteger CODES = new AtomicInteger();
    static final String OWNER = TestJwtIssuer.tokenFor(UUID.randomUUID(), Roles.ORGANIZER);

    @Autowired
    MockMvc mvc;

    @Test
    void defaultsAreNoReentryAndAllSessionsForACertificate() throws Exception {
        long id = create(null, null);

        mvc.perform(get("/api/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reentryAllowed").value(false))
                .andExpect(jsonPath("$.certificateMinSessions").doesNotExist());
    }

    @Test
    void bothSettingsAreStoredAndReturned() throws Exception {
        long id = create("true", "2");

        mvc.perform(get("/api/events/{id}", id))
                .andExpect(jsonPath("$.reentryAllowed").value(true))
                .andExpect(jsonPath("$.certificateMinSessions").value(2));
    }

    @Test
    void updateWithoutReentryKeepsItAndTheThresholdFollowsTheBody() throws Exception {
        long id = create("true", "3");
        String code = code(id);

        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reentryAllowed").value(true))
                .andExpect(jsonPath("$.certificateMinSessions").doesNotExist());
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, "false", "1")))
                .andExpect(jsonPath("$.reentryAllowed").value(false))
                .andExpect(jsonPath("$.certificateMinSessions").value(1));
    }

    @Test
    void aThresholdBelowOneIsRejected() throws Exception {
        mvc.perform(as(OWNER, post("/api/events")).content(body(nextCode(), null, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("certificateMinSessions"));
    }

    private long create(String reentry, String minSessions) throws Exception {
        String response = mvc.perform(as(OWNER, post("/api/events")).content(body(nextCode(), reentry, minSessions)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String code(long id) throws Exception {
        String response = mvc.perform(get("/api/events/{id}", id))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.code");
    }

    private static String nextCode() {
        return "CSET" + CODES.incrementAndGet();
    }

    private static String body(String code, String reentry, String minSessions) {
        return """
                {"code":"%s","title":"Check-in settings %s","categoryId":1,"venueId":1,"capacity":40,
                 "startsAt":"2099-11-14T03:30:00Z","endsAt":"2099-11-14T09:30:00Z",
                 "registrationOpensAt":"2099-10-01T03:30:00Z","registrationClosesAt":"2099-11-13T12:30:00Z",
                 "reentryAllowed":%s,"certificateMinSessions":%s}
                """.formatted(code, code, reentry, minSessions);
    }
}
