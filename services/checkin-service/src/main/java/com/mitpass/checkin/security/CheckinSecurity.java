package com.mitpass.checkin.security;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ownership rule for the attendance reports ({@code @checkinSecurity.ownsEvent(#eventId)}): an ADMIN, or the ORGANIZER
 * event-service lists as the event's organizer (it owns that fact; no copy is kept).
 */
@Component("checkinSecurity")
public class CheckinSecurity {

    private final EventCatalog catalog;

    public CheckinSecurity(EventCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * May scan at this event (the offline manifest, P7): the event's organizer (RL11), or a volunteer assigned to any of
     * its gates.
     */
    public boolean mayScan(Long eventId) {
        Optional<CurrentUser> caller = CurrentUser.current();
        if (caller.isEmpty() || eventId == null) {
            return false;
        }
        CurrentUser user = caller.get();
        if (user.hasRole(Roles.ORGANIZER)
                && user.id().equals(catalog.event(eventId).organizerId())) {
            return true;
        }
        return user.hasRole(Roles.VOLUNTEER)
                && catalog.assignmentsOf(user).stream().anyMatch(a -> eventId.equals(a.eventId()));
    }

    public boolean ownsEvent(Long eventId) {
        Optional<CurrentUser> caller = CurrentUser.current();
        if (caller.isEmpty() || eventId == null) {
            return false;
        }
        CurrentUser user = caller.get();
        if (user.hasRole(Roles.ADMIN)) {
            return true;
        }
        return user.hasRole(Roles.ORGANIZER)
                && user.id().equals(catalog.event(eventId).organizerId());
    }
}
