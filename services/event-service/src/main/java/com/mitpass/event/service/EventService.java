package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventSession;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.mapper.EventMapper;
import com.mitpass.event.repository.CategoryRepository;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.EventSpecifications;
import com.mitpass.event.repository.VenueRepository;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventSummaryResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import com.mitpass.event.web.dto.SessionRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event use cases. Each write is one transaction: an event and its initial sessions are stored together or not at all.
 * Rules needing the database return 422 ({@link BusinessRuleException}); clashes return 409 ({@link ConflictException}).
 */
@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository events;
    private final CategoryRepository categories;
    private final VenueRepository venues;
    private final EventMapper mapper;
    private final ZoneId zone;

    public EventService(
            EventRepository events,
            CategoryRepository categories,
            VenueRepository venues,
            EventMapper mapper,
            @Value("${mitpass.time-zone:Asia/Kolkata}") String timeZone) {
        this.events = events;
        this.categories = categories;
        this.venues = venues;
        this.mapper = mapper;
        this.zone = ZoneId.of(timeZone);
    }

    public PageResponse<EventSummaryResponse> search(EventSearchCriteria criteria, Pageable pageable) {
        return PageResponse.from(
                events.findAll(EventSpecifications.matching(criteria, zone), pageable), mapper::toSummary);
    }

    public EventDetailResponse get(long id) {
        return mapper.toDetail(loadDetailed(id));
    }

    /** The caller becomes the event's organizer (owner). */
    @Transactional
    public EventDetailResponse create(CurrentUser organizer, EventCreateRequest request) {
        if (events.existsByCode(request.code())) {
            throw duplicateCode(request.code());
        }
        Event event = new Event();
        mapper.apply(request, event);
        event.assignOrganizer(organizer.id(), organizer.name() != null ? organizer.name() : organizer.email());
        assignCategoryAndVenue(event, request.categoryId(), request.venueId());

        List<SessionRequest> sessions = request.sessions() == null ? List.of() : request.sessions();
        Set<Instant> starts = new HashSet<>();
        for (SessionRequest session : sessions) {
            CatalogRules.requireInsideEvent(event, session.title(), session.startsAt(), session.endsAt());
            if (!starts.add(session.startsAt())) {
                throw new BusinessRuleException(
                        "Two sessions cannot start at the same time (" + session.startsAt() + ")");
            }
            event.addSession(new EventSession(session.title(), session.startsAt(), session.endsAt()));
        }
        return mapper.toDetail(events.saveAndFlush(event));
    }

    @Transactional
    public EventDetailResponse update(long id, EventUpdateRequest request) {
        Event event = loadDetailed(id);
        if (request.version() != null && !request.version().equals(event.getVersion())) {
            throw new ConflictException(
                    "Event %d was changed by someone else (now version %d). Reload it and try again."
                            .formatted(id, event.getVersion()));
        }
        if (events.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode(request.code());
        }
        mapper.apply(request, event);
        assignCategoryAndVenue(event, request.categoryId(), request.venueId());
        for (EventSession session : event.getSessions()) {
            CatalogRules.requireInsideEvent(event, session.getTitle(), session.getStartsAt(), session.getEndsAt());
        }
        return mapper.toDetail(events.saveAndFlush(event));
    }

    @Transactional
    public void delete(long id) {
        Event event = events.findById(id).orElseThrow(() -> NotFoundException.of("Event", id));
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ConflictException(
                    "Only DRAFT events can be deleted; event %d is %s".formatted(id, event.getStatus()));
        }
        events.delete(event);
    }

    private Event loadDetailed(long id) {
        return events.findDetailedById(id).orElseThrow(() -> NotFoundException.of("Event", id));
    }

    private void assignCategoryAndVenue(Event event, Long categoryId, Long venueId) {
        Category category = categories
                .findById(categoryId)
                .orElseThrow(() -> new BusinessRuleException("Category " + categoryId + " does not exist"));
        Venue venue = venues.findById(venueId)
                .orElseThrow(() -> new BusinessRuleException("Venue " + venueId + " does not exist"));
        CatalogRules.requireCapacityFits(event.getCapacity(), venue);
        event.setCategory(category);
        event.setVenue(venue);
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("Event code '%s' is already taken".formatted(code));
    }
}
