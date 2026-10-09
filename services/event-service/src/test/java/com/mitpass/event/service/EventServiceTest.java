package com.mitpass.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.mapper.CategoryMapperImpl;
import com.mitpass.event.mapper.EventMapperImpl;
import com.mitpass.event.mapper.VenueMapperImpl;
import com.mitpass.event.repository.CategoryRepository;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.VenueRepository;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import com.mitpass.event.web.dto.SessionRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Domain rules of the service, with repositories mocked and the real (generated) mappers. */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant START = Instant.parse("2099-11-14T03:30:00Z");
    private static final Instant END = START.plusSeconds(86_400);

    @Mock
    EventRepository events;

    @Mock
    CategoryRepository categories;

    @Mock
    VenueRepository venues;

    EventService service;

    final Category technical = new Category("TECHNICAL", "Technical");
    final Venue lab = new Venue("Innovation Centre Lab", "Innovation Centre", 120, null);

    @BeforeEach
    void setUp() {
        EventMapperImpl mapper = new EventMapperImpl(new CategoryMapperImpl(), new VenueMapperImpl());
        service = new EventService(events, categories, venues, mapper, "Asia/Kolkata");
    }

    @Test
    void createsADraftWithItsSessions() {
        givenReferenceData();
        given(events.saveAndFlush(any(Event.class))).willAnswer(call -> call.getArgument(0));

        EventDetailResponse created = service.create(create(
                100,
                List.of(
                        new SessionRequest("Day 1", START, START.plusSeconds(3600)),
                        new SessionRequest("Day 2", START.plusSeconds(7200), END))));

        assertThat(created.code()).isEqualTo("HACK26");
        assertThat(created.category().code()).isEqualTo("TECHNICAL");
        assertThat(created.venue().name()).isEqualTo("Innovation Centre Lab");
        assertThat(created.sessions()).extracting("title").containsExactly("Day 1", "Day 2");
    }

    @Test
    void duplicateCodeIsAConflict() {
        given(events.existsByCode("HACK26")).willReturn(true);

        assertThatThrownBy(() -> service.create(create(100, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void capacityAboveTheVenueBreaksABusinessRule() {
        givenReferenceData();

        assertThatThrownBy(() -> service.create(create(500, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the capacity of venue 'Innovation Centre Lab' (120)");
    }

    @Test
    void unknownCategoryBreaksABusinessRule() {
        given(categories.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(create(100, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Category 1 does not exist");
    }

    @Test
    void sessionOutsideTheEventBreaksABusinessRule() {
        givenReferenceData();
        SessionRequest late = new SessionRequest("After party", END, END.plusSeconds(3600));

        assertThatThrownBy(() -> service.create(create(100, List.of(late))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must lie within the event");
    }

    @Test
    void twoSessionsStartingTogetherBreakABusinessRule() {
        givenReferenceData();
        SessionRequest a = new SessionRequest("A", START, START.plusSeconds(60));
        SessionRequest b = new SessionRequest("B", START, START.plusSeconds(120));

        assertThatThrownBy(() -> service.create(create(100, List.of(a, b))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("same time");
    }

    @Test
    void staleVersionOnUpdateIsAConflict() throws Exception {
        Event stored = storedEvent(3L);
        given(events.findDetailedById(1L)).willReturn(Optional.of(stored));

        EventUpdateRequest update =
                new EventUpdateRequest("HACK26", "New title", null, 1L, 1L, null, 100, START, END, null, null, 2L);

        assertThatThrownBy(() -> service.update(1L, update))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("now version 3");
    }

    @Test
    void onlyDraftEventsCanBeDeleted() throws Exception {
        Event published = storedEvent(0L);
        setField(published, "status", EventStatus.PUBLISHED);
        given(events.findById(5L)).willReturn(Optional.of(published));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only DRAFT events can be deleted; event 5 is PUBLISHED");
        verify(events, never()).delete(any(Event.class));
    }

    @Test
    void missingEventIsNotFound() {
        given(events.findDetailedById(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L)).isInstanceOf(NotFoundException.class);
    }

    private void givenReferenceData() {
        given(categories.findById(1L)).willReturn(Optional.of(technical));
        given(venues.findById(3L)).willReturn(Optional.of(lab));
    }

    private static EventCreateRequest create(int capacity, List<SessionRequest> sessions) {
        return new EventCreateRequest(
                "HACK26", "TechFest Hackathon", null, 1L, 3L, "CSE", capacity, START, END, null, null, sessions);
    }

    private Event storedEvent(long version) throws Exception {
        Event event = new Event();
        event.setCode("HACK26");
        event.setTitle("TechFest Hackathon");
        event.setCategory(technical);
        event.setVenue(lab);
        event.setCapacity(100);
        event.setStartsAt(START);
        event.setEndsAt(END);
        setField(event, "version", version);
        return event;
    }

    /** Test-only: set fields that only Hibernate writes (version, status before Phase 3 transitions). */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = Event.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
