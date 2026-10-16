package com.mitpass.event.domain;

import com.mitpass.common.web.state.LifecycleState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fetch strategy is explicit: both {@code @ManyToOne}s are LAZY (JPA's default would be EAGER) and {@code sessions} is
 * LAZY. Each query picks what it needs with an {@code @EntityGraph} in {@code EventRepository}.
 */
@Entity
public class Event extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Column(length = 80)
    private String department;

    @Column(nullable = false)
    private int capacity;

    /**
     * Seats held by CONFIRMED registrations. Read-only here: only the conditional UPDATEs in {@code EventRepository}
     * change it, so an organizer's edit (a normal entity save) can never overwrite it with a stale value (D10).
     */
    @Column(insertable = false, updatable = false)
    private int seatsTaken;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    private Instant registrationOpensAt;

    private Instant registrationClosesAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EventStatus status = EventStatus.DRAFT;

    /** Lowest year of study allowed to register; {@code null} = no lower limit. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer minYear;

    /** Highest year of study allowed to register; {@code null} = no upper limit. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer maxYear;

    /** May pass-service issue a long-lived (static) QR for the PDF ticket? The live code works either way (D12). */
    @Column(nullable = false)
    private boolean staticQrAllowed = true;

    /** {@code #RRGGBB} for the QR and ticket header; {@code null} = MITPass maroon. Upper case in the database. */
    @Column(length = 7)
    private String passColor;

    /** Department codes allowed to register; empty = every department. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "event_eligible_department", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "department_code", length = 16)
    private Set<String> eligibleDepartments = new TreeSet<>();

    /** The organizer who created the event (auth-service user id); only they and admins may change it. */
    @Column(nullable = false, updatable = false)
    private UUID organizerId;

    /** Display snapshot of the organizer's name, taken from their token when the event was created. */
    @Column(nullable = false, updatable = false, length = 120)
    private String organizerName;

    @Version
    private Long version;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startsAt ASC")
    private List<EventSession> sessions = new ArrayList<>();

    public Event() {}

    public void addSession(EventSession session) {
        session.setEvent(this);
        sessions.add(session);
    }

    /** True when {@code [from, to]} lies inside this event's own time window. */
    public boolean covers(Instant from, Instant to) {
        return !from.isBefore(startsAt) && !to.isAfter(endsAt);
    }

    /**
     * The only way to change the status (docs/DECISIONS.md D10): throws {@code IllegalTransitionException} (409) unless
     * the table allows the move and {@code allowedForCaller} contains it.
     */
    public void moveTo(EventStatus target, Set<EventStatus> allowedForCaller) {
        LifecycleState.require(status, target, allowedForCaller);
        this.status = target;
    }

    public void assignOrganizer(UUID organizerId, String organizerName) {
        this.organizerId = organizerId;
        this.organizerName = organizerName;
    }

    public UUID getOrganizerId() {
        return organizerId;
    }

    public String getOrganizerName() {
        return organizerName;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Venue getVenue() {
        return venue;
    }

    public void setVenue(Venue venue) {
        this.venue = venue;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getSeatsTaken() {
        return seatsTaken;
    }

    public int getSeatsLeft() {
        return Math.max(0, capacity - seatsTaken);
    }

    /** Registration ends at the closing time, or at the start when there is none. */
    public Instant registrationEndsAt() {
        return registrationClosesAt != null ? registrationClosesAt : startsAt;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Instant getRegistrationOpensAt() {
        return registrationOpensAt;
    }

    public void setRegistrationOpensAt(Instant registrationOpensAt) {
        this.registrationOpensAt = registrationOpensAt;
    }

    public Instant getRegistrationClosesAt() {
        return registrationClosesAt;
    }

    public void setRegistrationClosesAt(Instant registrationClosesAt) {
        this.registrationClosesAt = registrationClosesAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Integer getMinYear() {
        return minYear;
    }

    public boolean isStaticQrAllowed() {
        return staticQrAllowed;
    }

    public void setStaticQrAllowed(boolean staticQrAllowed) {
        this.staticQrAllowed = staticQrAllowed;
    }

    public String getPassColor() {
        return passColor;
    }

    public void setPassColor(String passColor) {
        this.passColor = passColor == null ? null : passColor.toUpperCase(java.util.Locale.ROOT);
    }

    public void setMinYear(Integer minYear) {
        this.minYear = minYear;
    }

    public Integer getMaxYear() {
        return maxYear;
    }

    public void setMaxYear(Integer maxYear) {
        this.maxYear = maxYear;
    }

    /** Sorted, read-only. */
    public List<String> getEligibleDepartments() {
        return List.copyOf(new TreeSet<>(eligibleDepartments));
    }

    /** Replaces the set; {@code null} or empty opens the event to every department. */
    public void setEligibleDepartments(List<String> codes) {
        eligibleDepartments.clear();
        if (codes != null) {
            eligibleDepartments.addAll(codes);
        }
    }

    public Long getVersion() {
        return version;
    }

    public List<EventSession> getSessions() {
        return Collections.unmodifiableList(sessions);
    }
}
