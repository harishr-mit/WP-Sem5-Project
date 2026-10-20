package com.mitpass.checkin.client;

import com.mitpass.common.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What checkin-service reads from event-service, the owner of events, sessions, gates and volunteer assignments (D14).
 * The HTTP version caches answers briefly (§4); tests use an in-memory one.
 */
public interface EventCatalog {

    /** {@code GET /api/events/{id}} (public). 404 → {@code NotFoundException}; unreachable → 503 problem. */
    EventInfo event(long eventId);

    /** {@code GET /api/events/{id}/gates}, with the current caller's token relayed. */
    List<GateInfo> gates(long eventId);

    /** Ids of the events this user organizes (public catalogue search by {@code organizerId}; RL11). */
    List<Long> eventsOrganizedBy(UUID organizerId);

    /** {@code GET /api/volunteers/me/assignments} with the volunteer's own token. */
    List<Assignment> assignmentsOf(CurrentUser volunteer);

    /**
     * Events a scanner could need now: LIVE, or open/closed for registration and starting before {@code until} and not
     * ended at {@code now}. Public catalogue search, so the reconciler needs no token for it.
     */
    List<EventInfo> scanningWindow(Instant now, Instant until);

    /** The fields of event-service's event detail that checkin-service uses; unknown fields are ignored. */
    record EventInfo(
            Long id,
            String code,
            String title,
            UUID organizerId,
            Instant startsAt,
            Instant endsAt,
            String status,
            Boolean reentryAllowed,
            Integer certificateMinSessions,
            List<SessionInfo> sessions,
            /** P7: the dashboard's capacity gauge. */
            Integer capacity) {

        public EventInfo {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }

        public boolean allowsReentry() {
            return Boolean.TRUE.equals(reentryAllowed);
        }

        /** Sessions an attendee can attend: at least 1 (an event without sessions is one implicit session). */
        public int sessionsTotal() {
            return Math.max(1, sessions.size());
        }

        /** Sessions needed for a certificate: the event's threshold, capped at the sessions that exist. */
        public int sessionsRequired() {
            int total = sessionsTotal();
            return certificateMinSessions == null ? total : Math.min(certificateMinSessions, total);
        }
    }

    record SessionInfo(Long id, String title, Instant startsAt, Instant endsAt) {}

    record GateInfo(Long id, String code, String name) {}

    /** {@code gate} null = any gate of the event. */
    record Assignment(Long eventId, String eventCode, String eventTitle, Instant eventStartsAt, GateInfo gate) {}
}
