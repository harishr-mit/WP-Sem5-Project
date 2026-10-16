package com.mitpass.pass.domain;

import com.mitpass.common.web.state.LifecycleState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A pass for one confirmed registration (PROJECT.md §3: owned by pass-service, "is the pass valid?"). Every state
 * change goes through {@link #moveTo}, backed by the {@link PassStatus} table; {@code @Version} stops the scheduler, a
 * consumer and an organizer from applying conflicting moves at once.
 */
@Entity
public class Pass extends AuditableEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 32, updatable = false)
    private String passNumber;

    @Column(nullable = false, updatable = false)
    private UUID registrationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private EventRef event;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 120)
    private String holderName;

    @Column(nullable = false, length = 254)
    private String holderEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PassStatus status;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    private Instant activatedAt;
    private Instant expiredAt;
    private Instant revokedAt;
    /** First check-in at a gate (from checkin-service's AttendanceMarked, P5). */
    private Instant checkedInAt;
    /** Set with the first check-in when the event allows no re-entry: the pass is used up (owner Q2, no new state). */
    private Instant usedAt;

    @Column(length = 64)
    private String revokedBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private RevokeReason revokeReason;

    @Column(length = 200)
    private String revokeNote;

    @Version
    private Long version;

    protected Pass() {}

    public Pass(
            UUID id,
            String passNumber,
            UUID registrationId,
            EventRef event,
            UUID userId,
            String holderName,
            String holderEmail,
            Instant issuedAt) {
        this.id = id;
        this.passNumber = passNumber;
        this.registrationId = registrationId;
        this.event = event;
        this.userId = userId;
        this.holderName = holderName;
        this.holderEmail = holderEmail;
        this.status = PassStatus.ISSUED;
        this.issuedAt = issuedAt;
    }

    /** Moves along the table (409 {@code illegal-transition} otherwise) and stamps the time of the new state. */
    public PassStatus moveTo(PassStatus target, Instant at) {
        PassStatus from = status;
        LifecycleState.require(from, target);
        status = target;
        switch (target) {
            case ACTIVE -> activatedAt = at;
            case EXPIRED -> expiredAt = at;
            case REVOKED -> revokedAt = at;
            case ISSUED -> throw new IllegalStateException("ISSUED is only a starting state");
        }
        return from;
    }

    public PassStatus revoke(Instant at, String by, RevokeReason reason, String note) {
        PassStatus from = moveTo(PassStatus.REVOKED, at);
        revokedBy = by;
        revokeReason = reason;
        revokeNote = note;
        return from;
    }

    public UUID getId() {
        return id;
    }

    public String getPassNumber() {
        return passNumber;
    }

    public UUID getRegistrationId() {
        return registrationId;
    }

    public EventRef getEvent() {
        return event;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getHolderEmail() {
        return holderEmail;
    }

    public PassStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    /**
     * Records a check-in reported by checkin-service. Only the first one counts; a redelivery or a later session
     * changes nothing. Works in any state: the gate decided, this is bookkeeping.
     */
    public void recordCheckIn(Instant at, boolean singleEntry) {
        if (checkedInAt == null) {
            checkedInAt = at;
            if (singleEntry) {
                usedAt = at;
            }
        }
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public RevokeReason getRevokeReason() {
        return revokeReason;
    }

    public String getRevokeNote() {
        return revokeNote;
    }

    public Long getVersion() {
        return version;
    }
}
