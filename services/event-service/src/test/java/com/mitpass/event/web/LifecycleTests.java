package com.mitpass.event.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Event state machine through the API (docs/plans/phase-3.md exit criterion 2). */
@EventIntegrationTest
public class LifecycleTests {

    static final AtomicInteger CODES = new AtomicInteger();
    static final UUID ORGANIZER = UUID.randomUUID();
    static final String OWNER = TestJwtIssuer.tokenFor(ORGANIZER, Roles.ORGANIZER);
    static final String OTHER = TestJwtIssuer.token(Roles.ORGANIZER);
    static final String ADMIN = TestJwtIssuer.token(Roles.ADMIN);

    @Autowired
    MockMvc mvc;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    com.mitpass.event.EventIntegrationTest.FakeCheckin checkin;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    long id;
    String code;

    @BeforeEach
    void createDraft() throws Exception {
        code = "LIFE" + CODES.incrementAndGet();
        String body = mvc.perform(as(OWNER, post("/api/events")).content(event(code, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.allowedTransitions", contains("PUBLISHED")))
                .andExpect(jsonPath("$.eligibleDepartments", contains("CSE", "IT")))
                .andExpect(jsonPath("$.minYear").value(2))
                .andExpect(jsonPath("$.maxYear").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        id = ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void organizerWalksTheHappyPathAndHistoryRecordsIt() throws Exception {
        move(OWNER, "PUBLISHED", "Ready")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.allowedTransitions", contains("REGISTRATION_OPEN", "CANCELLED")));
        move(ADMIN, "REGISTRATION_OPEN", null).andExpect(status().isOk());

        mvc.perform(as(OWNER, get("/api/events/{id}/history", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toStatus").value("DRAFT"))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[1].toStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$[1].changedBy").value(ORGANIZER.toString()))
                .andExpect(jsonPath("$[1].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[1].reason").value("Ready"))
                .andExpect(jsonPath("$[2].fromStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$[2].toStatus").value("REGISTRATION_OPEN"));
    }

    @Test
    void illegalMoveIs409WithTheAllowedStates() throws Exception {
        move(OWNER, "LIVE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:illegal-transition"))
                .andExpect(jsonPath("$.currentState").value("DRAFT"))
                .andExpect(jsonPath("$.requestedState").value("LIVE"))
                .andExpect(jsonPath("$.allowedStates", contains("PUBLISHED")));
    }

    @Test
    void onlyTheSchedulerMakesAnEventLive() throws Exception {
        toClosed();
        move(OWNER, "LIVE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.allowedStates", contains("CANCELLED")));

        lifecycle.transition(id, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, "Doors open");

        mvc.perform(get("/api/events/{id}", id))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.allowedTransitions", contains("REGISTRATION_CLOSED", "COMPLETED")));
        mvc.perform(as(OWNER, get("/api/events/{id}/history", id)))
                .andExpect(jsonPath("$[4].trigger").value("SCHEDULER"))
                .andExpect(jsonPath("$[4].changedBy").value("system"));
    }

    @Test
    void organizerCanUndoAnEarlyStartAndThenEditTheEventAgain() throws Exception {
        toClosed();
        lifecycle.transition(id, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, null);

        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(event(code, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("LIVE")));

        move(OWNER, "REGISTRATION_CLOSED", "Went live too early").andExpect(status().isOk());
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(event(code, 2)))
                .andExpect(status().isOk());
    }

    /** RL3 (D17): once anyone has checked in, LIVE can't be undone; if checkin-service can't say, it isn't risked. */
    @Test
    void undoingAStartIsRefusedOnceSomeoneHasCheckedInOrWhenCheckinServiceIsDown() throws Exception {
        toClosed();
        lifecycle.transition(id, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, null);
        try {
            checkin.down = true;
            move(OWNER, "REGISTRATION_CLOSED", "Too early").andExpect(status().isServiceUnavailable());
            checkin.down = false;

            checkin.withCheckIns.add(id);
            move(OWNER, "REGISTRATION_CLOSED", "Too early")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("already checked in")));
            mvc.perform(get("/api/events/{id}", id))
                    .andExpect(jsonPath("$.status").value("LIVE"));
            move(OWNER, "COMPLETED", null).andExpect(status().isOk());
        } finally {
            checkin.down = false;
            checkin.withCheckIns.remove(id);
        }
    }

    // ---- RL10 (D17): admin overrides --------------------------------------------------------------------------

    @Test
    void anAdminUnCancelsBackToTheStateItWasCancelledFromAndTheHistorySaysAdmin() throws Exception {
        move(OWNER, "PUBLISHED", null);
        move(OWNER, "CANCELLED", "Oops").andExpect(jsonPath("$.allowedTransitions", hasSize(0)));
        move(OWNER, "PUBLISHED", null).andExpect(status().isConflict());

        mvc.perform(as(ADMIN, get("/api/events/{id}", id)))
                .andExpect(jsonPath("$.allowedTransitions", contains("PUBLISHED")));
        move(ADMIN, "REGISTRATION_OPEN", null).andExpect(status().isConflict());
        move(ADMIN, "PUBLISHED", "Cancelled by mistake")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(as(ADMIN, get("/api/events/{id}/history", id)))
                .andExpect(jsonPath("$[3].toStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$[3].trigger").value("ADMIN"));
    }

    @Test
    void anAdminCanStartAnEventNowWhichMovesItsStartToNow() throws Exception {
        toClosed();
        move(OWNER, "LIVE", null).andExpect(status().isConflict());
        move(ADMIN, "LIVE", "Doors opened early")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.startsAt").value(not("2099-11-14T03:30:00Z")));
    }

    @Test
    void anAdminReopensACompletedEventOnlyWhileItsEndIsStillAhead() throws Exception {
        toClosed();
        lifecycle.transition(id, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, null);
        move(OWNER, "COMPLETED", null).andExpect(status().isOk());
        move(OWNER, "LIVE", null).andExpect(status().isConflict());

        jdbc.sql("UPDATE event SET starts_at = now() - interval '3 hours', ends_at = now() - interval '1 hour', "
                        + "registration_opens_at = NULL, registration_closes_at = NULL WHERE id = :id")
                .param("id", id)
                .update();
        move(ADMIN, "LIVE", null).andExpect(status().isUnprocessableContent());

        jdbc.sql("UPDATE event SET ends_at = now() + interval '2 hours' WHERE id = :id")
                .param("id", id)
                .update();
        move(ADMIN, "LIVE", "Ended by mistake").andExpect(status().isOk());
    }

    @Test
    void anAdminEditsALiveEventAnOrganizerCant() throws Exception {
        toClosed();
        lifecycle.transition(id, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, null);
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(event(code, 2)))
                .andExpect(status().isConflict());
        mvc.perform(as(ADMIN, put("/api/events/{id}", id)).content(event(code, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(42));
    }

    @Test
    void codeIsFrozenOnceRegistrationOpens() throws Exception {
        move(OWNER, "PUBLISHED", null);
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(event(code + "X", 1)))
                .andExpect(status().isOk());
        code = code + "X";
        move(OWNER, "REGISTRATION_OPEN", null);
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(event(code + "Y", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("code")));
    }

    @Test
    void cancelledIsFinal() throws Exception {
        move(OWNER, "PUBLISHED", null);
        move(OWNER, "CANCELLED", "Venue flooded").andExpect(jsonPath("$.allowedTransitions", hasSize(0)));
        move(OWNER, "PUBLISHED", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.allowedStates", hasSize(0)));
    }

    @Test
    void onlyOwnerOrAdminMayMoveOrReadHistory() throws Exception {
        move(OTHER, "PUBLISHED", null).andExpect(status().isForbidden());
        mvc.perform(as(OTHER, get("/api/events/{id}/history", id))).andExpect(status().isForbidden());
        mvc.perform(post("/api/events/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"PUBLISHED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTargetIs400AndMissingEventIs404() throws Exception {
        mvc.perform(as(OWNER, post("/api/events/{id}/transitions", id)).content("{\"to\":\"PARTY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(OWNER, post("/api/events/{id}/transitions", id)).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(ADMIN, post("/api/events/{id}/transitions", 999_999)).content("{\"to\":\"PUBLISHED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void yearRangeMustBeOrdered() throws Exception {
        String body = event("YR" + CODES.incrementAndGet(), 1).replace("\"minYear\":2", "\"minYear\":5");
        mvc.perform(as(OWNER, post("/api/events")).content(body)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void departmentCodesAreValidated() throws Exception {
        String body = event("DEP" + CODES.incrementAndGet(), 1).replace("\"IT\"", "\"it dept\"");
        mvc.perform(as(OWNER, post("/api/events")).content(body)).andExpect(status().isBadRequest());
    }

    private void toClosed() throws Exception {
        move(OWNER, "PUBLISHED", null).andExpect(status().isOk());
        move(OWNER, "REGISTRATION_OPEN", null).andExpect(status().isOk());
        move(OWNER, "REGISTRATION_CLOSED", null).andExpect(status().isOk());
    }

    private ResultActions move(String token, String to, String reason) throws Exception {
        String body = reason == null
                ? "{\"to\":\"%s\"}".formatted(to)
                : "{\"to\":\"%s\",\"reason\":\"%s\"}".formatted(to, reason);
        return mvc.perform(as(token, post("/api/events/{id}/transitions", id)).content(body));
    }

    public static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    /** Capacity varies so two updates differ; the event is far in the future so it can open. */
    static String event(String code, int capacityOffset) {
        return """
                {"code":"%s","title":"Lifecycle %s","categoryId":1,"venueId":1,"capacity":%d,
                 "startsAt":"2099-11-14T03:30:00Z","endsAt":"2099-11-14T09:30:00Z",
                 "registrationOpensAt":"2099-10-01T03:30:00Z","registrationClosesAt":"2099-11-13T12:30:00Z",
                 "eligibleDepartments":["CSE","IT"],"minYear":2,"maxYear":4}
                """.formatted(code, code, 40 + capacityOffset);
    }
}
