package com.mitpass.event.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.EventIntegrationTest.FakeAuthService;
import com.mitpass.event.client.AuthServiceClient.UserAccount;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Who may change an event, and the gate / volunteer staffing flow (docs/plans/phase-2.md exit criterion 7). */
@EventIntegrationTest
class OwnershipAndStaffingTests {

    static final AtomicInteger CODES = new AtomicInteger();

    static final UUID ORGANIZER_A = UUID.randomUUID();
    static final UUID ORGANIZER_B = UUID.randomUUID();
    static final String A = TestJwtIssuer.tokenFor(ORGANIZER_A, Roles.ORGANIZER);
    static final String B = TestJwtIssuer.tokenFor(ORGANIZER_B, Roles.ORGANIZER);
    static final String ADMIN = TestJwtIssuer.token(Roles.ADMIN);
    static final String STUDENT = TestJwtIssuer.token(Roles.STUDENT);

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeAuthService authService;

    long eventId;

    @BeforeEach
    void organizerACreatesAnEvent() throws Exception {
        String body = mvc.perform(as(A, post("/api/events")).content(event("OWN" + CODES.incrementAndGet())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId").value(ORGANIZER_A.toString()))
                .andExpect(jsonPath("$.organizerName").value("Test User"))
                .andExpect(jsonPath("$.createdBy").value(ORGANIZER_A.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        eventId = ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void onlyTheOwnerOrAnAdminMayChangeAnEvent() throws Exception {
        String update = event("UPD" + CODES.incrementAndGet());

        mvc.perform(as(B, put("/api/events/{id}", eventId)).content(update))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:forbidden"));
        mvc.perform(as(STUDENT, put("/api/events/{id}", eventId)).content(update))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/events/{id}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isUnauthorized());

        mvc.perform(as(A, put("/api/events/{id}", eventId)).content(update)).andExpect(status().isOk());
        mvc.perform(as(ADMIN, put("/api/events/{id}", eventId)).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerId").value(ORGANIZER_A.toString()));

        mvc.perform(as(B, delete("/api/events/{id}", eventId))).andExpect(status().isForbidden());
        mvc.perform(as(A, delete("/api/events/{id}", eventId))).andExpect(status().isNoContent());
    }

    @Test
    void readingTheCatalogueNeedsNoToken() throws Exception {
        mvc.perform(get("/api/events/{id}", eventId)).andExpect(status().isOk());
        mvc.perform(get("/api/events").param("organizerId", ORGANIZER_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].organizerId").value(ORGANIZER_A.toString()));
        mvc.perform(get("/api/events/{id}/sessions", eventId)).andExpect(status().isOk());
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    void studentsCannotCreateEventsAndAMissingEventIsA404NotA403() throws Exception {
        mvc.perform(as(STUDENT, post("/api/events")).content(event("STU" + CODES.incrementAndGet())))
                .andExpect(status().isForbidden());
        mvc.perform(as(B, put("/api/events/{id}", 999_999)).content(event("NONE" + CODES.incrementAndGet())))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizerStaffsTheirEvent() throws Exception {
        UserAccount volunteer = new UserAccount(
                UUID.randomUUID(),
                "Karthik Selvam",
                "karthik" + CODES.get() + "@gmail.com",
                List.of("STUDENT", "VOLUNTEER"));
        UserAccount student = new UserAccount(
                UUID.randomUUID(), "Priya Raman", "priya" + CODES.get() + "@gmail.com", List.of("STUDENT"));
        authService.register(volunteer);
        authService.register(student);

        String gate = mvc.perform(as(A, post("/api/events/{id}/gates", eventId))
                        .content("{\"code\":\"MAIN\",\"name\":\"Main Gate\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long gateId = ((Number) JsonPath.read(gate, "$.id")).longValue();
        mvc.perform(as(A, post("/api/events/{id}/gates", eventId)).content("{\"code\":\"MAIN\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(B, post("/api/events/{id}/gates", eventId)).content("{\"code\":\"SIDE\",\"name\":\"Side\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(as(A, post("/api/events/{id}/volunteers", eventId))
                        .content("{\"email\":\"%s\",\"gateId\":%d}".formatted(volunteer.email(), gateId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.volunteerId").value(volunteer.id().toString()))
                .andExpect(jsonPath("$.gate.code").value("MAIN"))
                .andExpect(jsonPath("$.assignedBy").value(ORGANIZER_A.toString()));
        mvc.perform(as(A, post("/api/events/{id}/volunteers", eventId))
                        .content("{\"email\":\"%s\"}".formatted(volunteer.email())))
                .andExpect(status().isConflict());
        mvc.perform(as(A, post("/api/events/{id}/volunteers", eventId))
                        .content("{\"email\":\"%s\"}".formatted(student.email())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not a volunteer")));
        mvc.perform(as(A, post("/api/events/{id}/volunteers", eventId)).content("{\"email\":\"nobody@gmail.com\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(as(A, get("/api/events/{id}/volunteers", eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(as(B, get("/api/events/{id}/volunteers", eventId))).andExpect(status().isForbidden());

        String volunteerToken = TestJwtIssuer.tokenFor(volunteer.id(), Roles.STUDENT, Roles.VOLUNTEER);
        mvc.perform(as(volunteerToken, get("/api/volunteers/me/assignments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].gate.code").value("MAIN"));
        mvc.perform(as(STUDENT, get("/api/volunteers/me/assignments"))).andExpect(status().isForbidden());

        // Removing the gate keeps the volunteer, now for any gate (FK ON DELETE SET NULL (gate_id)).
        mvc.perform(as(A, delete("/api/events/{id}/gates/{gateId}", eventId, gateId)))
                .andExpect(status().isNoContent());
        mvc.perform(as(volunteerToken, get("/api/volunteers/me/assignments")))
                .andExpect(jsonPath("$[0].gate").doesNotExist());

        mvc.perform(as(A, delete("/api/events/{id}/volunteers/{vid}", eventId, volunteer.id())))
                .andExpect(status().isNoContent());
        mvc.perform(as(volunteerToken, get("/api/volunteers/me/assignments"))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aGateOfAnotherEventIsRejected() throws Exception {
        long otherEvent = ((Number) JsonPath.read(
                        mvc.perform(as(B, post("/api/events")).content(event("OTH" + CODES.incrementAndGet())))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();
        long foreignGate = ((Number) JsonPath.read(
                        mvc.perform(as(B, post("/api/events/{id}/gates", otherEvent))
                                        .content("{\"code\":\"GATE-B\",\"name\":\"B's gate\"}"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();
        UserAccount volunteer = new UserAccount(
                UUID.randomUUID(), "Vol", "vol" + CODES.incrementAndGet() + "@gmail.com", List.of("VOLUNTEER"));
        authService.register(volunteer);

        mvc.perform(as(A, post("/api/events/{id}/volunteers", eventId))
                        .content("{\"email\":\"%s\",\"gateId\":%d}".formatted(volunteer.email(), foreignGate)))
                .andExpect(status().isUnprocessableContent());
    }

    static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    static String event(String code) {
        return """
                {"code":"%s","title":"Ownership test","categoryId":1,"venueId":1,"capacity":50,
                 "startsAt":"2099-12-05T04:30:00Z","endsAt":"2099-12-05T11:30:00Z"}
                """.formatted(code);
    }
}
