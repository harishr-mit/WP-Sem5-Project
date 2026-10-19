package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Session;
import com.mitpass.portal.client.ApiModels.Venue;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The organizer's event form (T08.6; PROJECT.md §P6 "MVC fundamentals"): {@code @ModelAttribute} form object, Bean
 * Validation and event-service's field errors in one {@code BindingResult}, the sessions sub-form, PRG, the stale
 * version 409, and the session sync on edit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizerFormsTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser organizer = TestTokens.user("ORGANIZER");

    @BeforeEach
    void referenceData() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.venues()).willReturn(List.of(new Venue(3L, "Main Auditorium", "Block A")));
        given(gateway.departments()).willReturn(List.of(new Department("CSE", "Computer Science")));
        given(gateway.events(any(), any(), any(), anyInt())).willReturn(new Page<>(List.of(), 0, 20, 0, 0));
    }

    MockHttpServletRequestBuilder validCreate() {
        return create("HackNite", "2026-11-29T06:00");
    }

    /** MockMvc's param() adds a value rather than replacing it, so the varied fields are parameters here. */
    MockHttpServletRequestBuilder create(String title, String endsAt) {
        return post("/organizer/events")
                .with(authentication(TestTokens.login(organizer)))
                .with(csrf())
                .param("code", "hacknite26")
                .param("title", title)
                .param("categoryId", "1")
                .param("venueId", "3")
                .param("capacity", "80")
                .param("startsAt", "2026-11-28T18:00")
                .param("endsAt", endsAt)
                .param("eligibleDepartments", "CSE")
                .param("staticQrAllowed", "true")
                .param("useDefaultColor", "true");
    }

    @Test
    void theFormShowsWithItsChoices() throws Exception {
        mvc.perform(get("/organizer/events/new").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Main Auditorium · Block A")))
                .andExpect(content().string(containsString("name=\"eligibleDepartments\"")))
                .andExpect(content().string(containsString("type=\"datetime-local\"")));
    }

    @Test
    void studentsCannotOpenTheForm() throws Exception {
        mvc.perform(get("/organizer/events/new").with(authentication(TestTokens.login(TestTokens.user("STUDENT")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePortalsOwnChecksShowNextToTheFieldsAndNothingIsSent() throws Exception {
        mvc.perform(create("", "2026-11-28T17:00"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "title", "endAfterStart"))
                .andExpect(content().string(containsString("must end after it starts")));
        verify(gateway, never()).createEvent(anyMap());
    }

    @Test
    void theServicesFieldErrorsLandOnTheSameFields() throws Exception {
        given(gateway.createEvent(anyMap()))
                .willThrow(new ApiProblemException(
                        400,
                        "urn:mitpass:problem:validation",
                        "Invalid",
                        List.of(
                                new FieldError("capacity", "is larger than the venue (60 seats)"),
                                new FieldError("sessions[0].title", "must not be blank")),
                        null));

        mvc.perform(validCreate())
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "capacity"))
                .andExpect(content().string(containsString("is larger than the venue (60 seats)")))
                // a field the form doesn't have (no session rows) becomes a form-level message
                .andExpect(content().string(containsString("must not be blank")));
    }

    @Test
    void creatingSendsCampusTimesAsInstantsAndRedirects() throws Exception {
        given(gateway.createEvent(anyMap())).willReturn(PortalPagesTest.detail(9, List.of(), "DRAFT", List.of()));

        mvc.perform(validCreate()
                        .param("sessions[0].title", "Night")
                        .param("sessions[0].startsAt", "2026-11-28T18:00")
                        .param("sessions[0].endsAt", "2026-11-28T23:00"))
                .andExpect(redirectedUrl("/organizer/events"))
                .andExpect(flash().attribute("message", containsString("saved as a draft")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).createEvent(body.capture());
        assertThat(body.getValue())
                .containsEntry("code", "HACKNITE26")
                .containsEntry("startsAt", "2026-11-28T12:30:00Z") // 18:00 IST
                .containsEntry("passColor", null)
                .containsEntry("eligibleDepartments", List.of("CSE"));
        assertThat((List<?>) body.getValue().get("sessions")).hasSize(1);
    }

    @Test
    void addSessionReRendersWithANewRowWithoutValidatingOrSaving() throws Exception {
        mvc.perform(create("", "2026-11-28T17:00").param("_addSession", "1"))
                .andExpect(status().isOk())
                .andExpect(model().hasNoErrors())
                .andExpect(content().string(containsString("name=\"sessions[0].title\"")));
        verify(gateway, never()).createEvent(anyMap());
    }

    @Test
    void removingASavedSessionRemembersItsIdForTheSave() throws Exception {
        given(gateway.event(9L)).willReturn(withSessions());

        mvc.perform(post("/organizer/events/9")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("sessions[0].id", "71")
                        .param("sessions[0].title", "Day 1")
                        .param("sessions[1].id", "72")
                        .param("sessions[1].title", "Day 2")
                        .param("_removeSession", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"removedSessionIds[0]\" value=\"71\"")))
                .andExpect(content().string(containsString("value=\"Day 2\"")))
                .andExpect(content().string(not(containsString("value=\"Day 1\""))));
    }

    @Test
    void savingAnEditUpdatesTheEventThenSyncsItsSessions() throws Exception {
        given(gateway.event(9L)).willReturn(withSessions());
        given(gateway.updateEvent(eq(9L), anyMap())).willReturn(withSessions());

        mvc.perform(post("/organizer/events/9")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("version", "3")
                        .param("code", "AIWS26")
                        .param("title", "AI Workshop")
                        .param("categoryId", "1")
                        .param("venueId", "3")
                        .param("capacity", "40")
                        .param("startsAt", "2026-11-28T09:00")
                        .param("endsAt", "2026-11-30T17:00")
                        .param("removedSessionIds[0]", "71")
                        .param("sessions[0].id", "72")
                        .param("sessions[0].title", "Day 2")
                        .param("sessions[0].startsAt", "2026-11-29T09:00")
                        .param("sessions[0].endsAt", "2026-11-29T17:00")
                        .param("sessions[1].title", "Day 3")
                        .param("sessions[1].startsAt", "2026-11-30T09:00")
                        .param("sessions[1].endsAt", "2026-11-30T17:00"))
                .andExpect(redirectedUrl("/organizer/events"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).updateEvent(eq(9L), body.capture());
        assertThat(body.getValue()).containsEntry("version", 3L).doesNotContainKey("sessions");
        verify(gateway).deleteSession(9L, 71L);
        verify(gateway).updateSession(eq(9L), eq(72L), anyMap());
        verify(gateway).addSession(eq(9L), anyMap());
    }

    @Test
    void aStaleVersionSaysReloadAndKeepsTheInput() throws Exception {
        given(gateway.event(9L)).willReturn(withSessions());
        given(gateway.updateEvent(eq(9L), anyMap()))
                .willThrow(new ApiProblemException(
                        409, "urn:mitpass:problem:conflict", "Event 9 was changed by someone else", null, null));

        mvc.perform(post("/organizer/events/9")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("version", "2")
                        .param("code", "AIWS26")
                        .param("title", "My new title")
                        .param("categoryId", "1")
                        .param("venueId", "3")
                        .param("capacity", "40")
                        .param("startsAt", "2026-11-28T09:00")
                        .param("endsAt", "2026-11-30T17:00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("changed by someone else")))
                .andExpect(content().string(containsString("Reload the event")))
                .andExpect(content().string(containsString("value=\"My new title\"")));
        verify(gateway, never()).updateSession(anyLong(), anyLong(), anyMap());
    }

    @Test
    void criticalMovesOpenADialogThatStillPostsConfirmed() throws Exception {
        PortalPagesTest.summary();
        given(gateway.events(any(), any(), any(), anyInt()))
                .willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 20, 1, 1));

        mvc.perform(get("/organizer/events").with(authentication(TestTokens.login(organizer))))
                .andExpect(content().string(containsString("data-open-dialog=\"move-1-CANCELLED\"")))
                .andExpect(content().string(containsString("<dialog class=\"confirm\" id=\"move-1-CANCELLED\"")))
                .andExpect(content().string(containsString("name=\"confirmed\" value=\"true\"")))
                .andExpect(content().string(containsString("Type the event code MINIQUIZ26 to confirm")));
    }

    @Test
    void myEventsPutsRunningAndUpcomingEventsBeforeFinishedOnes() throws Exception {
        var base = PortalPagesTest.summary();
        java.util.function.BiFunction<String, String, com.mitpass.portal.client.ApiModels.EventSummary> event =
                (title, state) -> new com.mitpass.portal.client.ApiModels.EventSummary(
                        base.id(),
                        base.code(),
                        title,
                        null,
                        null,
                        null,
                        10,
                        "Old done".equals(title) ? Instant.parse("2026-01-01T00:00:00Z") : base.startsAt(),
                        state,
                        0,
                        10,
                        List.of());
        given(gateway.events(any(), any(), any(), anyInt()))
                .willReturn(new Page<>(
                        List.of(event.apply("Old done", "COMPLETED"), event.apply("Coming up", "PUBLISHED")),
                        0,
                        20,
                        2,
                        1));

        String body = mvc.perform(get("/organizer/events").with(authentication(TestTokens.login(organizer))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body.indexOf("Coming up")).isLessThan(body.indexOf("Old done"));
    }

    static EventDetail withSessions() {
        EventDetail d = PortalPagesTest.detail(9, List.of(), "PUBLISHED", List.of());
        Instant day = Instant.parse("2026-11-28T03:30:00Z");
        return new EventDetail(
                d.id(),
                "AIWS26",
                "AI Workshop",
                d.description(),
                d.category(),
                new Venue(3L, "Main Auditorium", null),
                d.department(),
                40,
                day,
                day.plusSeconds(2 * 86_400),
                null,
                null,
                d.status(),
                d.organizerId(),
                d.organizerName(),
                List.of(),
                null,
                null,
                List.of(),
                0,
                40,
                3L,
                List.of(
                        new Session(71L, "Day 1", day, day.plusSeconds(8 * 3600)),
                        new Session(72L, "Day 2", day.plusSeconds(86_400), day.plusSeconds(86_400 + 8 * 3600))),
                true,
                null,
                false,
                null);
    }
}
