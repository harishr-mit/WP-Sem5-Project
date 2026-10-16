package com.mitpass.checkin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One presence move, for the audit trail (D10: every state machine keeps its history). */
@Entity
@Table(name = "presence_history")
public class PresenceChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID passId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PresenceState fromState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PresenceState toState;

    @Column(nullable = false)
    private long sessionId;

    @Column(nullable = false)
    private long gateId;

    @Column(nullable = false)
    private UUID scanId;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant changedAt;

    protected PresenceChange() {}

    public PresenceChange(
            UUID passId,
            PresenceState fromState,
            PresenceState toState,
            long sessionId,
            long gateId,
            UUID scanId,
            String actor,
            Instant changedAt) {
        this.passId = passId;
        this.fromState = fromState;
        this.toState = toState;
        this.sessionId = sessionId;
        this.gateId = gateId;
        this.scanId = scanId;
        this.actor = actor;
        this.changedAt = changedAt;
    }

    public PresenceState getFromState() {
        return fromState;
    }

    public PresenceState getToState() {
        return toState;
    }

    public long getSessionId() {
        return sessionId;
    }
}
