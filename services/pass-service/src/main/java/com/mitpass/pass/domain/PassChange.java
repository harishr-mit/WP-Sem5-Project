package com.mitpass.pass.domain;

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

/** One row of a pass's audited lifecycle ({@code pass_history}). Immutable once written. */
@Entity
@Table(name = "pass_history")
public class PassChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pass_id", updatable = false)
    private Pass pass;

    @Enumerated(EnumType.STRING)
    @Column(length = 8, updatable = false)
    private PassStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private PassStatus toStatus;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    @Column(nullable = false, length = 64, updatable = false)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TransitionTrigger trigger;

    @Column(length = 200, updatable = false)
    private String reason;

    protected PassChange() {}

    public PassChange(
            Pass pass,
            PassStatus fromStatus,
            PassStatus toStatus,
            Instant changedAt,
            String changedBy,
            TransitionTrigger trigger,
            String reason) {
        this.pass = pass;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
        this.trigger = trigger;
        this.reason = reason;
    }

    public PassStatus getFromStatus() {
        return fromStatus;
    }

    public PassStatus getToStatus() {
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
