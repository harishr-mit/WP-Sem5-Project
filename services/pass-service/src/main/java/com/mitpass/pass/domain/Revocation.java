package com.mitpass.pass.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** One entry of the scanners' revocation feed; {@code seq} is the cursor ({@code GET /api/revocations?since=}). */
@Entity
public class Revocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(nullable = false, updatable = false)
    private UUID passId;

    @Column(nullable = false, updatable = false)
    private Long eventId;

    @Column(nullable = false, updatable = false)
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private RevokeReason reason;

    protected Revocation() {}

    public Revocation(UUID passId, Long eventId, Instant revokedAt, RevokeReason reason) {
        this.passId = passId;
        this.eventId = eventId;
        this.revokedAt = revokedAt;
        this.reason = reason;
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getPassId() {
        return passId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevokeReason getReason() {
        return reason;
    }
}
