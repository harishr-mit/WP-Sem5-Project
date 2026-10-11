package com.mitpass.event.security;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.repository.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ownership rules for {@code @PreAuthorize}, e.g.
 * {@code @PreAuthorize("hasRole('ADMIN') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#id))")}.
 */
@Component("eventSecurity")
public class EventSecurity {

    private final EventRepository events;

    public EventSecurity(EventRepository events) {
        this.events = events;
    }

    /**
     * True if the caller organizes this event. A missing event also returns true, so the request reaches the
     * controller and gets the normal 404 instead of a misleading 403.
     */
    @Transactional(readOnly = true)
    public boolean isOwner(Long eventId) {
        return CurrentUser.current()
                .map(user -> events.findOrganizerIdById(eventId)
                        .map(organizer -> organizer.equals(user.id()))
                        .orElse(true))
                .orElse(false);
    }
}
