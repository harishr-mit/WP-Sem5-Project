package com.mitpass.checkin.domain;

import com.mitpass.common.web.state.LifecycleState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Where a pass holder is now (PROJECT.md §3). The check-in transaction creates the row (NOT_ARRIVED) if missing and
 * locks it ({@code SELECT … FOR UPDATE}), so two scans of one pass are decided one after the other.
 */
@Entity
@Table(name = "presence")
public class Presence {

    @Id
    private UUID passId;

    @Column(nullable = false)
    private long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PresenceState state;

    /** The session of the last move (0 = the whole event). */
    @Column(nullable = false)
    private long sessionId;

    @Column(nullable = false)
    private Instant changedAt;

    @Version
    private long version;

    protected Presence() {}

    public UUID getPassId() {
        return passId;
    }

    public long getEventId() {
        return eventId;
    }

    public PresenceState getState() {
        return state;
    }

    public long getSessionId() {
        return sessionId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    /** The only way to change state: the enum table decides (409 otherwise). Returns the state it came from. */
    public PresenceState moveTo(PresenceState target, long session, Instant at) {
        LifecycleState.require(state, target);
        PresenceState from = state;
        state = target;
        sessionId = session;
        changedAt = at;
        return from;
    }

    /** Same state, a new session (still inside from the last one). */
    public void enterSession(long session, Instant at) {
        sessionId = session;
        changedAt = at;
    }
}
