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
 * Every scan and lookup, accepted or not: the audit trail. {@code scannedAt} is the device's clock (P7's offline merge
 * needs it), {@code receivedAt} the server's.
 */
@Entity
@Table(name = "scan_log")
public class ScanLog {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private Instant scannedAt;

    @Column(nullable = false)
    private UUID volunteerId;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private long gateId;

    private Long eventId;
    private UUID passId;
    private String passNumber;
    private String tokenJti;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanResult result;

    private Long sessionId;
    private String detail;

    protected ScanLog() {}

    public ScanLog(
            UUID id,
            Instant receivedAt,
            Instant scannedAt,
            UUID volunteerId,
            String deviceId,
            long gateId,
            ScanMethod method,
            ScanMode mode) {
        this.id = id;
        this.receivedAt = receivedAt;
        this.scannedAt = scannedAt;
        this.volunteerId = volunteerId;
        this.deviceId = deviceId;
        this.gateId = gateId;
        this.method = method;
        this.mode = mode;
    }

    /** Fills in what the pipeline learned and the outcome. */
    public ScanLog outcome(
            ScanResult result,
            Long eventId,
            UUID passId,
            String passNumber,
            String jti,
            Long sessionId,
            String detail) {
        this.result = result;
        this.eventId = eventId;
        this.passId = passId;
        this.passNumber = passNumber;
        this.tokenJti = jti;
        this.sessionId = sessionId;
        this.detail = detail == null || detail.length() <= 200 ? detail : detail.substring(0, 200);
        return this;
    }

    public UUID getId() {
        return id;
    }

    public ScanResult getResult() {
        return result;
    }

    public ScanMethod getMethod() {
        return method;
    }
}
