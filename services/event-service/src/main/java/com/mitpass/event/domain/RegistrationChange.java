package com.mitpass.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row of a registration's audited lifecycle ({@code registration_history}). Immutable once written. */
@Entity
@Table(name = "registration_history")
public class RegistrationChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", updatable = false)
    private Registration registration;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, updatable = false)
    private RegistrationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private RegistrationStatus toStatus;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    @Column(nullable = false, length = 64, updatable = false)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TransitionTrigger trigger;

    @Column(length = 200, updatable = false)
    private String reason;

    protected RegistrationChange() {}

    public RegistrationChange(
            Registration registration,
            RegistrationStatus fromStatus,
            RegistrationStatus toStatus,
            Instant changedAt,
            String changedBy,
            TransitionTrigger trigger,
            String reason) {
        this.registration = registration;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
        this.trigger = trigger;
        this.reason = reason;
    }

    public RegistrationStatus getFromStatus() {
        return fromStatus;
    }

    public RegistrationStatus getToStatus() {
        return toStatus;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public TransitionTrigger getTrigger() {
        return trigger;
    }

    public String getReason() {
        return reason;
    }
}
