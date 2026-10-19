package com.mitpass.event.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unpublished events (owner decision, 2026-10-17, found while building the Phase 6 catalogue): a DRAFT is visible to
 * its own organizer and admins only. For everyone else it is a 404, in the list, on its page and its sessions.
 */
@EventIntegrationTest
class DraftVisibilityTests {

    static final AtomicInteger CODES = new AtomicInteger();

    static final UUID ORGANIZER_A = UUID.randomUUID();
    static final String A = TestJwtIssuer.tokenFor(ORGANIZER_A, Roles.ORGANIZER);
    static final String B = TestJwtIssuer.tokenFor(UUID.randomUUID(), Roles.ORGANIZER);
    static final String ADMIN = TestJwtIssuer.token(Roles.ADMIN);
    static final String STUDENT = TestJwtIssuer.token(Roles.STUDENT);

    @Autowired
    MockMvc mvc;

    long draftId;
    String code;

    @BeforeEach
    void organizerACreatesADraft() throws Exception {
        code = "DRAFT" + CODES.incrementAndGet() + (System.nanoTime() % 1000);
        String body = mvc.perform(OwnershipAndStaffingTests.as(A, post("/api/events"))
                        .content(OwnershipAndStaffingTests.event(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        draftId = ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void aDraftIsANotFoundForEveryoneButItsOrganizerAndAdmins() throws Exception {
        mvc.perform(get("/api/events/{id}", draftId)).andExpect(status().isNotFound());
        mvc.perform(OwnershipAndStaffingTests.as(STUDENT, get("/api/events/{id}", draftId)))
                .andExpect(status().isNotFound());
        mvc.perform(OwnershipAndStaffingTests.as(B, get("/api/events/{id}", draftId)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/events/{id}/sessions", draftId)).andExpect(status().isNotFound());

        mvc.perform(OwnershipAndStaffingTests.as(A, get("/api/events/{id}", draftId)))
                .andExpect(status().isOk());
        mvc.perform(OwnershipAndStaffingTests.as(ADMIN, get("/api/events/{id}", draftId)))
                .andExpect(status().isOk());
        mvc.perform(OwnershipAndStaffingTests.as(A, get("/api/events/{id}/sessions", draftId)))
                .andExpect(status().isOk());
    }

    @Test
    void theCatalogueLeavesDraftsOutExceptForTheirOrganizerAndAdmins() throws Exception {
        mvc.perform(get("/api/events")
                        .param("organizerId", ORGANIZER_A.toString())
                        .param("size", "100"))
                .andExpect(jsonPath("$.content[*].code", not(hasItem(code))));
        mvc.perform(get("/api/events").param("organizerId", ORGANIZER_A.toString()))
                .andExpect(jsonPath("$.content[*].code", not(hasItem(code))));
        mvc.perform(OwnershipAndStaffingTests.as(B, get("/api/events").param("organizerId", ORGANIZER_A.toString())))
                .andExpect(jsonPath("$.content[*].code", not(hasItem(code))));

        mvc.perform(OwnershipAndStaffingTests.as(A, get("/api/events").param("organizerId", ORGANIZER_A.toString())))
                .andExpect(jsonPath("$.content[*].code", hasItem(code)));
        mvc.perform(OwnershipAndStaffingTests.as(
                        ADMIN, get("/api/events").param("organizerId", ORGANIZER_A.toString())))
                .andExpect(jsonPath("$.content[*].code", hasItem(code)));
    }

    @Test
    void publishingMakesItPublic() throws Exception {
        mvc.perform(OwnershipAndStaffingTests.as(A, post("/api/events/{id}/transitions", draftId))
                        .content("{\"to\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/events/{id}", draftId)).andExpect(status().isOk());
        mvc.perform(get("/api/events").param("organizerId", ORGANIZER_A.toString()))
                .andExpect(jsonPath("$.content[*].code", hasItem(code)));
    }

    /** Phase 6 (D9 row): the list carries the allowed moves, so the organizer's page needs one call. */
    @Test
    void theListCarriesTheAllowedMoves() throws Exception {
        mvc.perform(OwnershipAndStaffingTests.as(A, get("/api/events").param("organizerId", ORGANIZER_A.toString())))
                .andExpect(
                        jsonPath("$.content[?(@.code == '" + code + "')].allowedTransitions[*]", hasItem("PUBLISHED")));
    }
}
