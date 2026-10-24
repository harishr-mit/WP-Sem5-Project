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
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.mapper.EventMapper;
import com.mitpass.event.repository.CategoryRepository;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.EventSpecifications;
import com.mitpass.event.repository.RegistrationRepository;
import com.mitpass.event.repository.VenueRepository;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventSummaryResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import com.mitpass.event.web.dto.SessionRequest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final EventLifecycleService lifecycle;
    private final WaitlistService waitlist;
    private final RegistrationRepository registrations;
    private final EntityManager entityManager;
    private final ZoneId zone;

    public EventService(
            EventRepository events,
            CategoryRepository categories,
            VenueRepository venues,
            EventMapper mapper,
            EventLifecycleService lifecycle,
            WaitlistService waitlist,
            RegistrationRepository registrations,
            EntityManager entityManager,
            @Value("${mitpass.time-zone:Asia/Kolkata}") String timeZone) {
        this.events = events;
        this.categories = categories;
        this.venues = venues;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.waitlist = waitlist;
        this.registrations = registrations;
        this.entityManager = entityManager;
        this.zone = ZoneId.of(timeZone);
    }

    /** DRAFT events are listed only for their organizer and admins ({@link DraftVisibility}). */
    public PageResponse<EventSummaryResponse> search(EventSearchCriteria criteria, Pageable pageable) {
        var visible =
                EventSpecifications.matching(criteria, zone).and(DraftVisibility.visibleTo(CurrentUser.current()));
        return PageResponse.from(events.findAll(visible, pageable), mapper::toSummary);
    }

    /** A DRAFT event is a 404 for anyone but its organizer and admins, like an unknown id. */
    public EventDetailResponse get(long id) {
        Event event = loadDetailed(id);
        if (!DraftVisibility.canSee(event, CurrentUser.current())) {
            throw NotFoundException.of("Event", id);
        }
        return mapper.toDetail(event);
    }

    /** The caller becomes the event's organizer (owner). */
    @Transactional
    public EventDetailResponse create(CurrentUser organizer, EventCreateRequest request) {
        if (events.existsByCode(request.code())) {
            throw duplicateCode(request.code());
        }
        CatalogRules.requireYearRange(request.minYear(), request.maxYear());
        Event event = new Event();
        mapper.apply(request, event);
        event.setEligibleDepartments(request.eligibleDepartments());
        event.setStaticQrAllowed(!Boolean.FALSE.equals(request.staticQrAllowed()));
        event.setReentryAllowed(Boolean.TRUE.equals(request.reentryAllowed()));
        event.setWaitlistEnabled(Boolean.TRUE.equals(request.waitlistEnabled()));
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
        Event saved = events.saveAndFlush(event);
        lifecycle.recordCreated(saved, organizer.id().toString());
        return mapper.toDetail(saved);
    }

    @Transactional
    public EventDetailResponse update(long id, EventUpdateRequest request) {
        Event event = loadDetailed(id);
        if (request.version() != null && !request.version().equals(event.getVersion())) {
            throw new ConflictException(
                    "Event %d was changed by someone else (now version %d). Reload it and try again."
                            .formatted(id, event.getVersion()));
        }
        if (!AllowedTransitions.isAdmin()) {
            CatalogRules.requireEditable(event); // admins edit in any state (RL10); every other rule still holds
        }
        CatalogRules.requireCodeUnchanged(event, request.code());
        CatalogRules.requireYearRange(request.minYear(), request.maxYear());
        if (events.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode(request.code());
        }
        int oldCapacity = event.getCapacity();
        int seatsTaken = events.findSeatsTakenById(id);
        if (request.capacity() < seatsTaken) {
            // A claim committing right after this check is still caught by the ck_event_seats constraint (409).
            throw new BusinessRuleException(
                    "Capacity %d is too low: %d seats are already taken".formatted(request.capacity(), seatsTaken));
        }
        Instant oldStart = event.getStartsAt();
        Instant oldEnd = event.getEndsAt();
        mapper.apply(request, event);
        event.setEligibleDepartments(request.eligibleDepartments());
        if (request.staticQrAllowed() != null) {
            event.setStaticQrAllowed(request.staticQrAllowed());
        }
        if (request.reentryAllowed() != null) {
            event.setReentryAllowed(request.reentryAllowed());
        }
        if (Boolean.FALSE.equals(request.waitlistEnabled()) && event.isWaitlistEnabled()) {
            long waiting = registrations.countByEventAndStatus(id, RegistrationStatus.WAITLISTED);
            if (waiting > 0) {
                throw new BusinessRuleException(
                        "The waitlist can't be turned off while %d student(s) are on it".formatted(waiting));
            }
        }
        if (request.waitlistEnabled() != null) {
            event.setWaitlistEnabled(request.waitlistEnabled());
        }
        assignCategoryAndVenue(event, request.categoryId(), request.venueId());
        for (EventSession session : event.getSessions()) {
            CatalogRules.requireInsideEvent(event, session.getTitle(), session.getStartsAt(), session.getEndsAt());
        }
        Event saved = events.saveAndFlush(event);
        if (!saved.getStartsAt().equals(oldStart) || !saved.getEndsAt().equals(oldEnd)) {
            lifecycle.recordRescheduled(saved, currentActor());
        }

        // New seats go to the waitlist first, in this transaction. The UPDATE above holds the event row lock.
        int added = saved.getCapacity() - oldCapacity;
        if (added > 0 && waitlist.promoteUpTo(id, added) > 0) {
            entityManager.refresh(saved); // the seat counter changed underneath the managed entity
        }
        return mapper.toDetail(saved);
    }

    /** The caller's user id for history and events; {@code system} outside a request. */
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "system";
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
