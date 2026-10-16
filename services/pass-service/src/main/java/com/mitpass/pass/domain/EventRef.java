package com.mitpass.pass.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What pass-service knows about an event: its times and status, kept up to date from domain events (written by
 * {@code EventRefStore} with SQL upserts, read here). Display data comes from event-service on demand.
 */
@Entity
@Table(name = "event_ref")
public class EventRef {

    @Id
    @Column(name = "event_id")
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Column(length = 24)
    private String status;

    protected EventRef() {}

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    /** The last {@code EventStateChanged.toStatus}, or null if none arrived yet. */
    public String getStatus() {
        return status;
    }
}
