package com.mitpass.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** An entry point of an event ("MAIN", "NORTH"). Volunteers are assigned to gates; scans record the gate (Phase 5). */
@Entity
public class EventGate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    protected EventGate() {}

    public EventGate(Event event, String code, String name) {
        this.event = event;
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
