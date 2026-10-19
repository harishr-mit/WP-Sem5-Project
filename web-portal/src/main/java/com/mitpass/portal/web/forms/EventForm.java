package com.mitpass.portal.web.forms;

import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The organizer's event form (create and edit; docs/plans/phase-6.md §5), bound with {@code @ModelAttribute}. The
 * checks here give quick feedback; event-service applies the real rules, and its 400 field errors land on the same
 * fields (the names match its request: {@code code}, {@code startsAt}, {@code sessions[0].title} …). Times are
 * entered in campus time ({@code datetime-local}) and sent as instants.
 *
 * <p>Sessions are a dynamic sub-form: "Add session" and "Remove" are submit buttons that re-render the form (no
 * JavaScript needed; HTMX swaps just the form). Removed rows that already exist in event-service are remembered in
 * {@link #removedSessionIds} and deleted on save.
 */
public class EventForm {

    public static final ZoneId CAMPUS = ZoneId.of("Asia/Kolkata");

    private Long version;

    @NotBlank
    @Pattern(regexp = "^[A-Z0-9]{3,16}$", message = "{eventForm.code.pattern}")
    private String code;

    @NotBlank
    @Size(min = 3, max = 160)
    private String title;

    @Size(max = 4000)
    private String description;

    @NotNull
    private Long categoryId;

    @NotNull
    private Long venueId;

    @NotNull
    @Positive
    @Max(100_000)
    private Integer capacity;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startsAt;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endsAt;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime registrationOpensAt;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime registrationClosesAt;

    private List<String> eligibleDepartments = new ArrayList<>();

    @Min(1)
    @Max(5)
    private Integer minYear;

    @Min(1)
    @Max(5)
    private Integer maxYear;

    private boolean staticQrAllowed = true;

    private boolean useDefaultColor = true;

    @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "{eventForm.color.pattern}")
    private String passColor = "#7A1F3D";

    private boolean reentryAllowed;

    @Min(1)
    @Max(50)
    private Integer certificateMinSessions;

    private List<@Valid SessionRow> sessions = new ArrayList<>();

    private List<Long> removedSessionIds = new ArrayList<>();

    /** A blank form: starts next week at 10:00 campus time, two hours long. */
    public static EventForm blank(LocalDateTime now) {
        EventForm form = new EventForm();
        LocalDateTime start =
                now.plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0);
        form.startsAt = start;
        form.endsAt = start.plusHours(2);
        return form;
    }

    /** The form for an existing event. */
    public static EventForm of(EventDetail event) {
        EventForm form = new EventForm();
        form.version = event.version();
        form.code = event.code();
        form.title = event.title();
        form.description = event.description();
        form.categoryId = event.category() != null ? event.category().id() : null;
        form.venueId = event.venue() != null ? event.venue().id() : null;
        form.capacity = event.capacity();
        form.startsAt = local(event.startsAt());
        form.endsAt = local(event.endsAt());
        form.registrationOpensAt = local(event.registrationOpensAt());
        form.registrationClosesAt = local(event.registrationClosesAt());
        form.eligibleDepartments =
                event.eligibleDepartments() == null ? new ArrayList<>() : new ArrayList<>(event.eligibleDepartments());
        form.minYear = event.minYear();
        form.maxYear = event.maxYear();
        form.staticQrAllowed = !Boolean.FALSE.equals(event.staticQrAllowed());
        form.useDefaultColor = event.passColor() == null;
        if (event.passColor() != null) {
            form.passColor = event.passColor();
        }
        form.reentryAllowed = Boolean.TRUE.equals(event.reentryAllowed());
        form.certificateMinSessions = event.certificateMinSessions();
        if (event.sessions() != null) {
            for (Session s : event.sessions()) {
                form.sessions.add(new SessionRow(s.id(), s.title(), local(s.startsAt()), local(s.endsAt())));
            }
        }
        return form;
    }

    @AssertTrue(message = "{eventForm.endsAfterStart}")
    public boolean isEndAfterStart() {
        return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
    }

    @AssertTrue(message = "{eventForm.yearsInOrder}")
    public boolean isYearsInOrder() {
        return minYear == null || maxYear == null || minYear <= maxYear;
    }

    /** The JSON body for {@code POST /api/events} ({@code withSessions}) or {@code PUT /api/events/{id}}. */
    public Map<String, Object> toRequest(boolean withSessions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code == null ? null : code.strip().toUpperCase(java.util.Locale.ROOT));
        body.put("title", title == null ? null : title.strip());
        body.put("description", description == null || description.isBlank() ? null : description.strip());
        body.put("categoryId", categoryId);
        body.put("venueId", venueId);
        body.put("capacity", capacity);
        body.put("startsAt", instant(startsAt));
        body.put("endsAt", instant(endsAt));
        body.put("registrationOpensAt", instant(registrationOpensAt));
        body.put("registrationClosesAt", instant(registrationClosesAt));
        body.put("eligibleDepartments", eligibleDepartments == null ? List.of() : eligibleDepartments);
        body.put("minYear", minYear);
        body.put("maxYear", maxYear);
        body.put("staticQrAllowed", staticQrAllowed);
        body.put("passColor", useDefaultColor || passColor == null || passColor.isBlank() ? null : passColor);
        body.put("reentryAllowed", reentryAllowed);
        body.put("certificateMinSessions", certificateMinSessions);
        if (withSessions) {
            body.put("sessions", sessions.stream().map(SessionRow::toRequest).toList());
        } else {
            body.put("version", version);
        }
        return body;
    }

    public static String instant(LocalDateTime local) {
        return local == null ? null : local.atZone(CAMPUS).toInstant().toString();
    }

    public static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, CAMPUS);
    }

    /** One session row. {@code id} is null for a row not saved yet. */
    public static class SessionRow {

        private Long id;

        @NotBlank
        @Size(max = 160)
        private String title;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startsAt;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endsAt;

        public SessionRow() {}

        public SessionRow(Long id, String title, LocalDateTime startsAt, LocalDateTime endsAt) {
            this.id = id;
            this.title = title;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
        }

        @AssertTrue(message = "{eventForm.endsAfterStart}")
        public boolean isEndAfterStart() {
            return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
        }

        public Map<String, Object> toRequest() {
            Map<String, Object> body = new HashMap<>();
            body.put("title", title == null ? null : title.strip());
            body.put("startsAt", instant(startsAt));
            body.put("endsAt", instant(endsAt));
            return body;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDateTime getStartsAt() {
            return startsAt;
        }

        public void setStartsAt(LocalDateTime startsAt) {
            this.startsAt = startsAt;
        }

        public LocalDateTime getEndsAt() {
            return endsAt;
        }

        public void setEndsAt(LocalDateTime endsAt) {
            this.endsAt = endsAt;
        }
    }

    // ---- getters and setters (data binding) ----------------------------------------------------------------------

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getCode() {
        return code;
    }

    /** Upper-cased on the way in, so "techfest26" passes the check as it would after saving. */
    public void setCode(String code) {
        this.code = code == null ? null : code.strip().toUpperCase(java.util.Locale.ROOT);
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getVenueId() {
        return venueId;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(LocalDateTime startsAt) {
        this.startsAt = startsAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(LocalDateTime endsAt) {
        this.endsAt = endsAt;
    }

    public LocalDateTime getRegistrationOpensAt() {
        return registrationOpensAt;
    }

    public void setRegistrationOpensAt(LocalDateTime registrationOpensAt) {
        this.registrationOpensAt = registrationOpensAt;
    }

    public LocalDateTime getRegistrationClosesAt() {
        return registrationClosesAt;
    }

    public void setRegistrationClosesAt(LocalDateTime registrationClosesAt) {
        this.registrationClosesAt = registrationClosesAt;
    }

    public List<String> getEligibleDepartments() {
        return eligibleDepartments;
    }

    public void setEligibleDepartments(List<String> eligibleDepartments) {
        this.eligibleDepartments = eligibleDepartments == null ? new ArrayList<>() : eligibleDepartments;
    }

    public Integer getMinYear() {
        return minYear;
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

    public boolean isStaticQrAllowed() {
        return staticQrAllowed;
    }

    public void setStaticQrAllowed(boolean staticQrAllowed) {
        this.staticQrAllowed = staticQrAllowed;
    }

    public boolean isUseDefaultColor() {
        return useDefaultColor;
    }

    public void setUseDefaultColor(boolean useDefaultColor) {
        this.useDefaultColor = useDefaultColor;
    }

    public String getPassColor() {
        return passColor;
    }

    public void setPassColor(String passColor) {
        this.passColor = passColor;
    }

    public boolean isReentryAllowed() {
        return reentryAllowed;
    }

    public void setReentryAllowed(boolean reentryAllowed) {
        this.reentryAllowed = reentryAllowed;
    }

    public Integer getCertificateMinSessions() {
        return certificateMinSessions;
    }

    public void setCertificateMinSessions(Integer certificateMinSessions) {
        this.certificateMinSessions = certificateMinSessions;
    }

    public List<SessionRow> getSessions() {
        return sessions;
    }

    public void setSessions(List<SessionRow> sessions) {
        this.sessions = sessions == null ? new ArrayList<>() : sessions;
    }

    public List<Long> getRemovedSessionIds() {
        return removedSessionIds;
    }

    public void setRemovedSessionIds(List<Long> removedSessionIds) {
        this.removedSessionIds = removedSessionIds == null ? new ArrayList<>() : removedSessionIds;
    }
}
