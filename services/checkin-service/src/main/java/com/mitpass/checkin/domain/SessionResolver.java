package com.mitpass.checkin.domain;

import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Which session a scan counts for (docs/plans/phase-5.md §4). An event without sessions is one implicit session
 * ({@code id 0}) for its whole time. With sessions: the one running now wins; otherwise the next one whose doors are
 * open ({@code doorsOpen} before its start). Overlapping windows (doors of session 3 open while session 2 still runs)
 * resolve to the session in progress.
 */
public final class SessionResolver {

    public enum State {
        OPEN,
        NOT_YET_OPEN,
        CLOSED
    }

    /** {@code sessionId}/{@code title} when OPEN; {@code opensAt} when NOT_YET_OPEN. */
    public record Resolution(State state, long sessionId, String title, Instant opensAt) {

        public boolean open() {
            return state == State.OPEN;
        }
    }

    private SessionResolver() {}

    /**
     * Assumes the event itself is LIVE (checked before). Without sessions the whole event counts; its status already
     * says whether doors are open.
     */
    public static Resolution resolve(
            List<SessionInfo> sessions, Instant eventStart, Instant eventEnd, Instant now, Duration doorsOpen) {
        if (sessions.isEmpty()) {
            return now.isBefore(eventEnd)
                    ? new Resolution(State.OPEN, 0, null, null)
                    : new Resolution(State.CLOSED, 0, null, null);
        }
        List<SessionInfo> ordered = sessions.stream()
                .sorted(Comparator.comparing(SessionInfo::startsAt))
                .toList();
        for (SessionInfo session : ordered) {
            if (!now.isBefore(session.startsAt()) && now.isBefore(session.endsAt())) {
                return new Resolution(State.OPEN, session.id(), session.title(), null);
            }
        }
        for (SessionInfo session : ordered) {
            Instant doors = session.startsAt().minus(doorsOpen);
            if (now.isBefore(session.startsAt())) {
                return now.isBefore(doors)
                        ? new Resolution(State.NOT_YET_OPEN, 0, null, doors)
                        : new Resolution(State.OPEN, session.id(), session.title(), null);
            }
        }
        return new Resolution(State.CLOSED, 0, null, null);
    }
}
