package com.mitpass.event.domain;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.state.LifecycleState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A student's registration for an event (PROJECT.md §3). Status changes only through {@link #moveTo}, which checks
 * {@link RegistrationStatus}'s table. The student's name, email, department and year are snapshots from their token,
 * so organizer lists and pass-service (P4) need no call to auth-service.
 */
@Entity
public class Registration extends AuditableEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", updatable = false)
    private Event event;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 120)
    private String userName;

    @Column(nullable = false, updatable = false, length = 254)
    private String userEmail;

    @Column(updatable = false, length = 16)
    private String department;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(updatable = false)
    private Integer yearOfStudy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RegistrationStatus status = RegistrationStatus.PENDING;

    /** FIFO order on the waitlist (from {@code registration_waitlist_seq}); set when waitlisted. */
    private Long waitlistSeq;

    private Instant confirmedAt;
    private Instant waitlistedAt;
    private Instant cancelledAt;

    @Column(length = 64)
    private String cancelledBy;

    @Column(nullable = false)
    private boolean viaPromotion;

    @Version
    private Long version;

    protected Registration() {}

    public Registration(Event event, CurrentUser student) {
        this.event = event;
        this.userId = student.id();
        this.userName = student.name() != null ? student.name() : student.email();
        this.userEmail = student.email();
        this.department = student.department();
        this.yearOfStudy = student.year();
    }

    public void confirm(Instant at, boolean promotion) {
        moveTo(RegistrationStatus.CONFIRMED);
        this.confirmedAt = at;
        this.viaPromotion = promotion;
        this.waitlistSeq = null;
    }

    public void waitlist(Instant at, long seq) {
        moveTo(RegistrationStatus.WAITLISTED);
        this.waitlistedAt = at;
        this.waitlistSeq = seq;
    }

    public void cancel(Instant at, String by) {
        moveTo(RegistrationStatus.CANCELLED);
        this.cancelledAt = at;
        this.cancelledBy = by;
        this.waitlistSeq = null;
    }

    private void moveTo(RegistrationStatus target) {
        LifecycleState.require(status, target);
        this.status = target;
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getDepartment() {
        return department;
    }

    public Integer getYearOfStudy() {
        return yearOfStudy;
    }

    public RegistrationStatus getStatus() {
        return status;
    }

    public Long getWaitlistSeq() {
        return waitlistSeq;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getWaitlistedAt() {
        return waitlistedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public boolean isViaPromotion() {
        return viaPromotion;
    }

    public Long getVersion() {
        return version;
    }
}
