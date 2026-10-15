package com.mitpass.event.web;

import static com.mitpass.event.web.LifecycleTests.as;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.service.EventService;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Registration endpoints over HTTP (docs/plans/phase-3.md §7). */
@EventIntegrationTest
public class RegistrationApiTests {

    static final AtomicInteger CODES = new AtomicInteger();
    static final String ORGANIZER = TestJwtIssuer.tokenFor(TestData.ORGANIZER_ID, Roles.ORGANIZER);
    static final String OTHER_ORGANIZER = TestJwtIssuer.token(Roles.ORGANIZER);
    static final String ADMIN = TestJwtIssuer.token(Roles.ADMIN);

    @Autowired
    MockMvc mvc;

    @Autowired
    EventService events;

    @Autowired
    EventLifecycleService lifecycle;

    long eventId;

    @BeforeEach
    void oneSeatEventForCseOnly() {
        eventId = events.create(
                        TestData.ORGANIZER_USER,
                        TestData.createRequest("API" + CODES.incrementAndGet(), 1, List.of("CSE"), null, null))
                .id();
        lifecycle.transition(eventId, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(eventId, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
    }

    @Test
    void registerConfirmsThenWaitlistsWithLocation() throws Exception {
        register(student("CSE", 2))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/registrations/")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.department").value("CSE"))
                .andExpect(jsonPath("$.year").value(2));
        register(student("CSE", 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITLISTED"))
                .andExpect(jsonPath("$.waitlistPosition").value(1));
    }

    @Test
    void problemsCarryTheirTypes() throws Exception {
        String cse = student("CSE", 1);
        register(cse).andExpect(status().isCreated());
        register(cse)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:already-registered"));
        register(student("AERO", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:not-eligible"))
                .andExpect(jsonPath("$.reasons[0]").value("Open to CSE only (you are in AERO)"));
        mvc.perform(as(cse, post("/api/registrations")).content("{\"eventId\":999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(cse, post("/api/registrations")).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void onlyStudentsRegister() throws Exception {
        mvc.perform(as(ORGANIZER, post("/api/registrations")).content(body())).andExpect(status().isForbidden());
        mvc.perform(post("/api/registrations").contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentSeesOwnRegistrationsAndOthersCannot() throws Exception {
        String mine = student("CSE", 2);
        String id = idOf(register(mine));

        mvc.perform(as(mine, get("/api/registrations/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(as(mine, get("/api/registrations/{id}", id))).andExpect(status().isOk());
        mvc.perform(as(ORGANIZER, get("/api/registrations/{id}", id))).andExpect(status().isOk());
        mvc.perform(as(ADMIN, get("/api/registrations/{id}", id))).andExpect(status().isOk());

        mvc.perform(as(student("CSE", 2), get("/api/registrations/{id}", id))).andExpect(status().isForbidden());
        mvc.perform(as(OTHER_ORGANIZER, get("/api/registrations/{id}", id))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, get("/api/registrations/{id}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelPromotesAndReportsWho() throws Exception {
        String first = student("CSE", 2);
        String firstId = idOf(register(first));
        String secondId = idOf(register(student("CSE", 2)));

        mvc.perform(as(student("CSE", 2), post("/api/registrations/{id}/cancel", firstId)))
                .andExpect(status().isForbidden());
        mvc.perform(as(first, post("/api/registrations/{id}/cancel", firstId)).content("{\"reason\":\"Ill\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.promotedRegistrationId").value(secondId));
        mvc.perform(as(first, post("/api/registrations/{id}/cancel", firstId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:illegal-transition"));
    }

    @Test
    void organizerCancelsAndListsTheirEventsRegistrations() throws Exception {
        String id = idOf(register(student("CSE", 2)));
        register(student("CSE", 2));

        mvc.perform(as(ORGANIZER, get("/api/events/{id}/registrations", eventId))
                        .param("status", "WAITLISTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(OTHER_ORGANIZER, get("/api/events/{id}/registrations", eventId)))
                .andExpect(status().isForbidden());
        mvc.perform(as(ORGANIZER, post("/api/registrations/{id}/cancel", id))).andExpect(status().isOk());
    }

    @Test
    void eligibilityTellsTheStudentBeforeTheyTry() throws Exception {
        String cse = student("CSE", 2);
        mvc.perform(as(cse, get("/api/events/{id}/eligibility", eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.registrationOpen").value(true))
                .andExpect(jsonPath("$.seatsLeft").value(1))
                .andExpect(jsonPath("$.myRegistration").doesNotExist());

        register(cse);
        mvc.perform(as(cse, get("/api/events/{id}/eligibility", eventId)))
                .andExpect(jsonPath("$.seatsLeft").value(0))
                .andExpect(jsonPath("$.myRegistration.status").value("CONFIRMED"));

        mvc.perform(as(student("ECE", 2), get("/api/events/{id}/eligibility", eventId)))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons[0]").value(endsWith("(you are in ECE)")));
        mvc.perform(get("/api/events/{id}/eligibility", eventId)).andExpect(status().isUnauthorized());
    }

    @Test
    void eventResponsesShowSeats() throws Exception {
        register(student("CSE", 2));
        mvc.perform(get("/api/events/{id}", eventId))
                .andExpect(jsonPath("$.seatsTaken").value(1))
                .andExpect(jsonPath("$.seatsLeft").value(0));
    }

    private ResultActions register(String token) throws Exception {
        return mvc.perform(as(token, post("/api/registrations")).content(body()));
    }

    private String body() {
        return "{\"eventId\":%d}".formatted(eventId);
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A fresh student with department and year claims, as auth-service issues them. */
    public static String student(String department, int year) {
        return TestJwtIssuer.sign(TestJwtIssuer.claims(UUID.randomUUID(), Roles.STUDENT)
                .claim("dept", department)
                .claim("year", year)
                .build());
    }
}
