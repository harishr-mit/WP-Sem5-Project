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

/** One row of an event's audited lifecycle ({@code event_state_history}). Immutable once written. */
@Entity
@Table(name = "event_state_history")
public class EventStateChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", updatable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, updatable = false)
    private EventStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private EventStatus toStatus;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    @Column(nullable = false, length = 64, updatable = false)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TransitionTrigger trigger;

    @Column(length = 200, updatable = false)
    private String reason;

    protected EventStateChange() {}

    public EventStateChange(
            Event event,
            EventStatus fromStatus,
            EventStatus toStatus,
            Instant changedAt,
            String changedBy,
            TransitionTrigger trigger,
            String reason) {
        this.event = event;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
        this.trigger = trigger;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public EventStatus getFromStatus() {
        return fromStatus;
    }

    public EventStatus getToStatus() {
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
