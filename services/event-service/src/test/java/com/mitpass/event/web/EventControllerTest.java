package com.mitpass.event.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.common.security.Roles;
import com.mitpass.common.web.CommonWebAutoConfiguration;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.service.EventSearchCriteria;
import com.mitpass.event.service.EventService;
import com.mitpass.event.web.dto.CategoryResponse;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.VenueResponse;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestSecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The web layer alone: binding, Bean Validation and error mapping, with the service mocked. */
@WebMvcTest(EventController.class)
@Import({CommonWebAutoConfiguration.class, TestSecurityConfig.class})
class EventControllerTest {

    /** Writes need a token now (Phase 2); these tests are about behaviour, so they act as ADMIN. */
    static final String ADMIN = "Bearer " + TestJwtIssuer.token(Roles.ADMIN);

    private static final String VALID = """
            {
              "code": "HACK26",
              "title": "TechFest Hackathon",
              "categoryId": 1,
              "venueId": 3,
              "capacity": 100,
              "startsAt": "2099-11-14T03:30:00Z",
              "endsAt": "2099-11-15T03:30:00Z",
              "sessions": [
                {"title": "Day 1", "startsAt": "2099-11-14T03:30:00Z", "endsAt": "2099-11-14T18:00:00Z"}
              ]
            }
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventService events;

    @Test
    void createReturns201WithLocation() throws Exception {
        given(events.create(any(), any())).willReturn(detail(42L));

        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/events/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void invalidPayloadIsA400ProblemWithEveryFieldError() throws Exception {
        String invalid = """
                {
                  "code": "hack 26",
                  "title": "",
                  "categoryId": 1,
                  "capacity": 0,
                  "startsAt": "2099-11-15T03:30:00Z",
                  "endsAt": "2099-11-14T03:30:00Z",
                  "registrationOpensAt": "2099-11-20T00:00:00Z",
                  "sessions": [{"title": "", "startsAt": "2099-11-14T05:00:00Z", "endsAt": "2099-11-14T04:00:00Z"}]
                }
                """;

        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:validation"))
                .andExpect(jsonPath("$.instance").value("/api/events"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("code")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("venueId")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("capacity")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("endsAt")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("registrationOpensAt")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("sessions[0].title")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("sessions[0].endsAt")))
                .andExpect(jsonPath("$.errors[*].message", hasItem("must be after startsAt")));
    }

    @Test
    void startInThePastIsRejected() throws Exception {
        String past = """
                {"code": "OLD01", "title": "Last year", "categoryId": 1, "venueId": 3, "capacity": 10,
                 "startsAt": "2001-01-01T00:00:00Z", "endsAt": "2001-01-01T02:00:00Z"}
                """;
        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(past))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startsAt"));
    }

    @Test
    void unknownIdIsA404Problem() throws Exception {
        given(events.get(7L)).willThrow(NotFoundException.of("Event", 7L));

        mvc.perform(get("/api/events/7"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Event 7 was not found"));
    }

    @Test
    void businessRuleIsA422AndConflictA409() throws Exception {
        given(events.create(any(), any())).willThrow(new BusinessRuleException("Capacity 100 exceeds the venue"));
        mvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:business-rule"));

        willThrow(new ConflictException("Only DRAFT events can be deleted"))
                .given(events)
                .delete(5L);
        mvc.perform(delete("/api/events/5").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:conflict"));
    }

    @Test
    void searchBindsEveryFilterAndThePage() throws Exception {
        given(events.search(any(), any())).willReturn(new PageResponse<>(List.of(), 1, 5, 0, 0));

        mvc.perform(get("/api/events")
                        .param("q", "hack")
                        .param("category", "TECHNICAL")
                        .param("department", "CSE")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30")
                        .param("status", "PUBLISHED")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "title,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content").isArray());

        ArgumentCaptor<EventSearchCriteria> criteria = ArgumentCaptor.forClass(EventSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(events).search(criteria.capture(), pageable.capture());
        assertThat(criteria.getValue())
                .isEqualTo(new EventSearchCriteria(
                        "hack",
                        "TECHNICAL",
                        "CSE",
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 30),
                        EventStatus.PUBLISHED,
                        null));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("title: DESC");
    }

    @Test
    void badSearchParameterIsA400() throws Exception {
        mvc.perform(get("/api/events").param("from", "next-week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value 'next-week'"));
        mvc.perform(get("/api/events").param("status", "OPEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/events/5").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNoContent());
        verify(events).delete(eq(5L));
    }

    private static EventDetailResponse detail(long id) {
        Instant start = Instant.parse("2099-11-14T03:30:00Z");
        return new EventDetailResponse(
                id,
                "HACK26",
                "TechFest Hackathon",
                null,
                new CategoryResponse(1L, "TECHNICAL", "Technical"),
                new VenueResponse(3L, "Innovation Centre Lab", "Innovation Centre", 120, null, 0L),
                null,
                100,
                start,
                start.plusSeconds(86_400),
                null,
                null,
                EventStatus.DRAFT,
                java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"),
                "Anitha Rao",
                0L,
                List.of(),
                Instant.now(),
                "system",
                Instant.now(),
                "system",
                List.of(),
                null,
                null,
                List.of(EventStatus.PUBLISHED),
                0,
                100,
                true,
                null,
                false,
                null,
                false);
    }
}
