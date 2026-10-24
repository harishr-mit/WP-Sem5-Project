package com.mitpass.event.web;

import static com.mitpass.event.web.LifecycleTests.as;
import static com.mitpass.event.web.RegistrationApiTests.student;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.service.EventService;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** RL1 (D17): the waitlist is a per-event setting, off by default, open after closing until LIVE. */
@EventIntegrationTest
class WaitlistSettingTests {

    static final AtomicInteger CODES = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    EventService events;

    @Autowired
    EventLifecycleService lifecycle;

    @Test
    void newEventsHaveNoWaitlistSoAFullEventRefuses() throws Exception {
        long id = openEvent(1, null);
        assertThat(events.get(id).waitlistEnabled()).isFalse();

        register(id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        register(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:event-full"));
    }

    @Test
    void withAWaitlistAFullEventWaitlists() throws Exception {
        long id = openEvent(1, true);
        register(id).andExpect(jsonPath("$.status").value("CONFIRMED"));
        register(id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITLISTED"));
    }

    @Test
    void afterClosingAWaitlistedEventStillTakesWaitlistEntriesEvenWithSeatsFree() throws Exception {
        long id = openEvent(5, true);
        close(id);

        String late = student("CSE", 2);
        mvc.perform(as(late, get("/api/events/{id}/eligibility", id)))
                .andExpect(jsonPath("$.registrationOpen").value(true))
                .andExpect(jsonPath("$.waitlistOnly").value(true));
        register(id, late)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITLISTED"))
                .andExpect(jsonPath("$.waitlistPosition").value(1));
    }

    @Test
    void afterClosingWithoutAWaitlistRegistrationIsRefused() throws Exception {
        long id = openEvent(5, false);
        close(id);
        register(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:registration-closed"));
    }

    @Test
    void theWaitlistCantBeTurnedOffWhileSomeoneWaits() throws Exception {
        long id = openEvent(1, true);
        register(id);
        register(id).andExpect(jsonPath("$.status").value("WAITLISTED"));

        assertThatThrownBy(() -> events.update(id, waitlist(events.get(id), false)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1 student(s)");

        long empty = openEvent(1, true);
        assertThat(events.update(empty, waitlist(events.get(empty), false)).waitlistEnabled())
                .isFalse();
    }

    private long openEvent(int capacity, Boolean waitlist) {
        EventCreateRequest base =
                TestData.createRequest("WL" + CODES.incrementAndGet(), capacity, List.of(), null, null);
        EventCreateRequest request = new EventCreateRequest(
                base.code(),
                base.title(),
                base.description(),
                base.categoryId(),
                base.venueId(),
                base.department(),
                base.capacity(),
                base.startsAt(),
                base.endsAt(),
                base.registrationOpensAt(),
                base.registrationClosesAt(),
                base.sessions(),
                base.eligibleDepartments(),
                base.minYear(),
                base.maxYear(),
                base.staticQrAllowed(),
                base.passColor(),
                base.reentryAllowed(),
                base.certificateMinSessions(),
                waitlist);
        long id = events.create(TestData.ORGANIZER_USER, request).id();
        lifecycle.transition(id, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
        return id;
    }

    private void close(long id) {
        lifecycle.transition(id, EventStatus.REGISTRATION_CLOSED, "test", TransitionTrigger.MANUAL, null);
    }

    private static EventUpdateRequest waitlist(EventDetailResponse current, boolean on) {
        return new EventUpdateRequest(
                current.code(),
                current.title(),
                current.description(),
                current.category().id(),
                current.venue().id(),
                current.department(),
                current.capacity(),
                current.startsAt(),
                current.endsAt(),
                current.registrationOpensAt(),
                current.registrationClosesAt(),
                current.version(),
                current.eligibleDepartments(),
                current.minYear(),
                current.maxYear(),
                null,
                current.passColor(),
                null,
                current.certificateMinSessions(),
                on);
    }

    private ResultActions register(long eventId) throws Exception {
        return register(eventId, student("CSE", 2));
    }

    private ResultActions register(long eventId, String token) throws Exception {
        return mvc.perform(as(token, post("/api/registrations")).content("{\"eventId\":%d}".formatted(eventId)));
    }
}
