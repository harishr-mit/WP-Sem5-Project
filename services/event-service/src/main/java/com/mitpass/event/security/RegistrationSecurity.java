package com.mitpass.event.security;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.RegistrationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may read or cancel a registration, for {@code @PreAuthorize("@registrationSecurity.canAccess(#id)")}: the
 * student it belongs to, the organizer of its event, or an admin.
 */
@Component("registrationSecurity")
public class RegistrationSecurity {

    private final RegistrationRepository registrations;
    private final EventRepository events;

    public RegistrationSecurity(RegistrationRepository registrations, EventRepository events) {
        this.registrations = registrations;
        this.events = events;
    }

    /** A missing registration returns true, so the controller answers with the normal 404 instead of a 403. */
    @Transactional(readOnly = true)
    public boolean canAccess(UUID registrationId) {
        Optional<CurrentUser> caller = CurrentUser.current();
        if (caller.isEmpty()) {
            return false;
        }
        CurrentUser user = caller.get();
        if (user.hasRole(Roles.ADMIN)) {
            return true;
        }
        Optional<UUID> owner = registrations.findUserIdById(registrationId);
        if (owner.isEmpty() || owner.get().equals(user.id())) {
            return true;
        }
        return user.hasRole(Roles.ORGANIZER)
                && registrations
                        .findEventIdById(registrationId)
                        .flatMap(events::findOrganizerIdById)
                        .map(user.id()::equals)
                        .orElse(false);
    }
}
