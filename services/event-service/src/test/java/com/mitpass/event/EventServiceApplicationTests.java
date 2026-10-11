package com.mitpass.event;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole service (web → service → JPA → PostgreSQL via Testcontainers) in one flow: create, read, search, update,
 * sessions, error cases, OpenAPI. Mirrors the Phase 1 exit criteria minus the gateway.
 */
@EventIntegrationTest
class EventServiceApplicationTests {

    /** Writes need a token now (Phase 2); these tests are about behaviour, so they act as ADMIN. */
    static final java.util.UUID ADMIN_ID = java.util.UUID.randomUUID();

    static final String ADMIN = "Bearer " + TestJwtIssuer.tokenFor(ADMIN_ID, Roles.ADMIN);

    @Autowired
    MockMvc mvc;

    @Test
    void eventLifecycleThroughTheApi() throws Exception {
        String body = """
                {
                  "code": "FLOW26",
                  "title": "Full Flow Workshop",
                  "description": "End-to-end test event",
                  "categoryId": 4,
                  "venueId": 3,
                  "department": "CSE",
                  "capacity": 60,
                  "startsAt": "2099-12-05T04:30:00Z",
                  "endsAt": "2099-12-05T11:30:00Z",
                  "sessions": [
                    {"title": "Morning", "startsAt": "2099-12-05T04:30:00Z", "endsAt": "2099-12-05T06:30:00Z"}
                  ]
                }
                """;

        String created = mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.category.code").value("WORKSHOP"))
                .andExpect(jsonPath("$.sessions[0].title").value("Morning"))
                .andExpect(jsonPath("$.createdBy").value(ADMIN_ID.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // duplicate code → 409
        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        // search finds it by text, category and day (Asia/Kolkata)
        mvc.perform(get("/api/events")
                        .param("q", "full flow")
                        .param("category", "WORKSHOP")
                        .param("from", "2099-12-05")
                        .param("to", "2099-12-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("FLOW26"))
                .andExpect(jsonPath("$.content[0].venueName").value("Innovation Centre Lab"));

        // add a session, then one outside the event window → 422
        mvc.perform(post("/api/events/{id}/sessions", id)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Afternoon", "startsAt": "2099-12-05T08:00:00Z", "endsAt": "2099-12-05T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/events/{id}/sessions", id)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Too late", "startsAt": "2099-12-05T12:00:00Z", "endsAt": "2099-12-05T13:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/events/{id}/sessions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("Afternoon")));

        // update with the right version, then replay the stale version → 409
        String update = """
                {
                  "code": "FLOW26", "title": "Full Flow Workshop (updated)", "categoryId": 4, "venueId": 3,
                  "capacity": 50, "startsAt": "2099-12-05T04:30:00Z", "endsAt": "2099-12-05T11:30:00Z", "version": 0
                }
                """;
        mvc.perform(put("/api/events/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Full Flow Workshop (updated)"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/events/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isConflict());

        // capacity above the venue → 422
        mvc.perform(put("/api/events/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update.replace("\"capacity\": 50", "\"capacity\": 5000")
                                .replace("\"version\": 0", "\"version\": 1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:business-rule"));

        // DRAFT can be deleted; then it's gone
        mvc.perform(delete("/api/events/{id}", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/events/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void invalidPayloadIsAStructured400() throws Exception {
        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:validation"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.errors[*].field", hasItem("title")));
    }

    @Test
    void unknownSortPropertyIsA400NotA500() throws Exception {
        mvc.perform(get("/api/events").param("sort", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:malformed-request"));
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/events").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void referenceDataEndpoints() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("TECHNICAL")));
        mvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MITPass event-service"))
                .andExpect(jsonPath("$.paths['/api/events']").exists());
    }
}
