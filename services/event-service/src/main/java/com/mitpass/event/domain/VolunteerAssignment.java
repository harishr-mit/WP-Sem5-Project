package com.mitpass.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.util.UUID;

/**
 * A volunteer staffing an event, at one gate or (gate {@code null}) at any gate. The volunteer's name and email are
 * snapshots from auth-service, so listing assignments needs no cross-service call. Phase 5's scanner checks these.
 */
@Entity
public class VolunteerAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gate_id")
    private EventGate gate;

    @Column(nullable = false)
    private UUID volunteerId;

    @Column(nullable = false, length = 120)
    private String volunteerName;

    @Column(nullable = false, length = 254)
    private String volunteerEmail;

    @Column(nullable = false)
    private Instant assignedAt;

    @Column(nullable = false, length = 64)
    private String assignedBy;

    protected VolunteerAssignment() {}

    public VolunteerAssignment(
            Event event,
            EventGate gate,
            UUID volunteerId,
            String volunteerName,
            String volunteerEmail,
            Instant assignedAt,
            String assignedBy) {
        this.event = event;
        this.gate = gate;
        this.volunteerId = volunteerId;
        this.volunteerName = volunteerName;
        this.volunteerEmail = volunteerEmail;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public EventGate getGate() {
        return gate;
    }

    public UUID getVolunteerId() {
        return volunteerId;
    }

    public String getVolunteerName() {
        return volunteerName;
    }

    public String getVolunteerEmail() {
        return volunteerEmail;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public String getAssignedBy() {
        return assignedBy;
    }
}
