package com.mitpass.event.service;

import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventSession;
import com.mitpass.event.mapper.EventMapper;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.EventSessionRepository;
import com.mitpass.event.web.dto.SessionRequest;
import com.mitpass.event.web.dto.SessionResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sessions of an event, edited in place so their ids stay stable for attendance (Phase 5). */
@Service
@Transactional(readOnly = true)
public class SessionService {

    private final EventRepository events;
    private final EventSessionRepository sessions;
    private final EventMapper mapper;

    public SessionService(EventRepository events, EventSessionRepository sessions, EventMapper mapper) {
        this.events = events;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public List<SessionResponse> list(long eventId) {
        if (!events.existsById(eventId)) {
            throw NotFoundException.of("Event", eventId);
        }
        return sessions.findByEventIdOrderByStartsAtAsc(eventId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public SessionResponse add(long eventId, SessionRequest request) {
        Event event = events.findDetailedById(eventId).orElseThrow(() -> NotFoundException.of("Event", eventId));
        CatalogRules.requireEditable(event);
        CatalogRules.requireInsideEvent(event, request.title(), request.startsAt(), request.endsAt());
        boolean clash =
                event.getSessions().stream().anyMatch(s -> s.getStartsAt().equals(request.startsAt()));
        if (clash) {
            throw new ConflictException(
                    "Event %d already has a session starting at %s".formatted(eventId, request.startsAt()));
        }
        EventSession session = new EventSession(request.title(), request.startsAt(), request.endsAt());
        event.addSession(session);
        events.saveAndFlush(event);
        return mapper.toResponse(session);
    }

    @Transactional
    public SessionResponse update(long eventId, long sessionId, SessionRequest request) {
        EventSession session = load(eventId, sessionId);
        CatalogRules.requireEditable(session.getEvent());
        CatalogRules.requireInsideEvent(session.getEvent(), request.title(), request.startsAt(), request.endsAt());
        session.setTitle(request.title());
        session.setStartsAt(request.startsAt());
        session.setEndsAt(request.endsAt());
        return mapper.toResponse(sessions.saveAndFlush(session));
    }

    @Transactional
    public void delete(long eventId, long sessionId) {
        EventSession session = load(eventId, sessionId);
        CatalogRules.requireEditable(session.getEvent());
        sessions.delete(session);
    }

    private EventSession load(long eventId, long sessionId) {
        return sessions.findByIdAndEventId(sessionId, eventId)
                .orElseThrow(() ->
                        new NotFoundException("Session %d of event %d was not found".formatted(sessionId, eventId)));
    }
}
