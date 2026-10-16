package com.mitpass.pass.security;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.pass.client.EventCatalogClient;
import com.mitpass.pass.repository.PassRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may do what with a pass (docs/DECISIONS.md D12), for {@code @PreAuthorize("@passSecurity.…")}. QR codes, tokens
 * and the PDF are for the holder only: an organizer who could fetch them could check students in on their behalf. The
 * event's organizer and admins see and revoke passes; the organizer comes from event-service (cached).
 *
 * <p>A missing pass returns true from the pass checks, so the controller answers with the normal 404 instead of 403.
 */
@Component("passSecurity")
public class PassSecurity {

    private final PassRepository passes;
    private final EventCatalogClient catalog;

    public PassSecurity(PassRepository passes, EventCatalogClient catalog) {
        this.passes = passes;
        this.catalog = catalog;
    }

    /** The pass belongs to the caller. */
    @Transactional(readOnly = true)
    public boolean isHolder(UUID passId) {
        Optional<CurrentUser> caller = CurrentUser.current();
        if (caller.isEmpty()) {
            return false;
        }
        return passes.findUserIdById(passId).map(caller.get().id()::equals).orElse(true);
    }

    /** The holder, the event's organizer, or an admin. */
    @Transactional(readOnly = true)
    public boolean canView(UUID passId) {
        return isHolder(passId) || canManage(passId);
    }

    /** The event's organizer or an admin. */
    @Transactional(readOnly = true)
    public boolean canManage(UUID passId) {
        Optional<CurrentUser> caller = CurrentUser.current();
        if (caller.isEmpty()) {
            return false;
        }
        if (caller.get().hasRole(Roles.ADMIN)) {
            return true;
        }
        Optional<Long> eventId = passes.findEventIdById(passId);
        if (eventId.isEmpty()) {
            return true;
        }
        return ownsEvent(eventId.get());
    }

    /** The caller is an ADMIN, or the ORGANIZER who owns the event. */
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
                && user.id().equals(catalog.get(eventId).organizerId());
    }
}
