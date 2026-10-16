package com.mitpass.checkin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One pass checked in for one session ({@code sessionId} 0 = the whole event). {@code UNIQUE (pass_id, session_id)}
 * is the database's guarantee that a pass counts once per session, whatever the application does.
 */
@Entity
@Table(name = "attendance")
public class Attendance {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID passId;

    @Column(nullable = false)
    private long eventId;

    @Column(nullable = false)
    private long sessionId;

    @Column(nullable = false)
    private long gateId;

    @Column(nullable = false)
    private String gateCode;

    @Column(nullable = false)
    private UUID volunteerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanMethod method;

    @Column(nullable = false)
    private Instant checkedInAt;

    @Column(nullable = false)
    private Instant scannedAt;

    protected Attendance() {}

    public Attendance(
            UUID passId,
            long eventId,
            long sessionId,
            long gateId,
            String gateCode,
            UUID volunteerId,
            ScanMethod method,
            Instant checkedInAt,
            Instant scannedAt) {
        this.id = UUID.randomUUID();
        this.passId = passId;
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.gateId = gateId;
        this.gateCode = gateCode;
        this.volunteerId = volunteerId;
        this.method = method;
        this.checkedInAt = checkedInAt;
        this.scannedAt = scannedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPassId() {
        return passId;
    }

    public long getEventId() {
        return eventId;
    }

    public long getSessionId() {
        return sessionId;
    }

    public long getGateId() {
        return gateId;
    }

    public String getGateCode() {
        return gateCode;
    }

    public UUID getVolunteerId() {
        return volunteerId;
    }

    public ScanMethod getMethod() {
        return method;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }
}
