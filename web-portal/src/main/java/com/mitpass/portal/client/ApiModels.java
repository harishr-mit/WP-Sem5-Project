package com.mitpass.portal.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The portal's view of the backend JSON. Tolerant readers: only the fields the pages use, unknown fields ignored, so
 * the services can add fields freely (AGENTS.md: published contracts only grow). Numbers and flags are boxed because
 * Jackson 3 refuses a missing value for a primitive ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default).
 */
public final class ApiModels {

    private ApiModels() {}

    public record TokenResponse(String accessToken, long expiresIn, String refreshToken) {
        /** Never print tokens. */
        @Override
        public String toString() {
            return "TokenResponse[expiresIn=" + expiresIn + "]";
        }
    }

    public record Department(String code, String name) {}

    public record Category(Long id, String code, String name) {}

    public record Venue(Long id, String name, String building) {}

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public boolean hasNext() {
            return page + 1 < totalPages;
        }

        public boolean hasPrevious() {
            return page > 0;
        }
    }

    public record EventSummary(
            Long id,
            String code,
            String title,
            String categoryName,
            String venueName,
            String department,
            Integer capacity,
            Instant startsAt,
            String status,
            Integer seatsTaken,
            Integer seatsLeft,
            /** Phase 6: the moves allowed now (event-service adds them to the list too). */
            List<String> allowedTransitions) {

        /** Seats taken as a percentage of capacity, for the seats bar. */
        public int fillPercent() {
            return percent(seatsTaken, capacity);
        }
    }

    static int percent(Integer part, Integer whole) {
        if (part == null || whole == null || whole <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(part * 100.0 / whole));
    }

    /** One session of an event (multi-session workshops). */
    public record Session(Long id, String title, Instant startsAt, Instant endsAt) {}

    /** The catalogue's search: every field optional (event-service {@code GET /api/events}). */
    public record EventQuery(
            String q,
            String category,
            String department,
            java.time.LocalDate from,
            java.time.LocalDate to,
            boolean past,
            int page) {

        public boolean isEmpty() {
            return blank(q) && blank(category) && blank(department) && from == null && to == null;
        }

        /** What is sent to event-service: without a start date and without "past", events from today on. */
        public EventQuery effective(java.time.LocalDate today) {
            return from != null || past ? this : new EventQuery(q, category, department, today, to, past, page);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    public record EventDetail(
            Long id,
            String code,
            String title,
            String description,
            Category category,
            Venue venue,
            String department,
            Integer capacity,
            Instant startsAt,
            Instant endsAt,
            Instant registrationOpensAt,
            Instant registrationClosesAt,
            String status,
            UUID organizerId,
            String organizerName,
            List<String> eligibleDepartments,
            Integer minYear,
            Integer maxYear,
            List<String> allowedTransitions,
            Integer seatsTaken,
            Integer seatsLeft,
            Long version,
            List<Session> sessions,
            Boolean staticQrAllowed,
            String passColor,
            Boolean reentryAllowed,
            Integer certificateMinSessions,
            Boolean waitlistEnabled) {

        public EventDetail(
                Long id,
                String code,
                String title,
                String description,
                Category category,
                Venue venue,
                String department,
                Integer capacity,
                Instant startsAt,
                Instant endsAt,
                Instant registrationOpensAt,
                Instant registrationClosesAt,
                String status,
                UUID organizerId,
                String organizerName,
                List<String> eligibleDepartments,
                Integer minYear,
                Integer maxYear,
                List<String> allowedTransitions,
                Integer seatsTaken,
                Integer seatsLeft,
                Long version,
                List<Session> sessions,
                Boolean staticQrAllowed,
                String passColor,
                Boolean reentryAllowed,
                Integer certificateMinSessions) {
            this(
                    id,
                    code,
                    title,
                    description,
                    category,
                    venue,
                    department,
                    capacity,
                    startsAt,
                    endsAt,
                    registrationOpensAt,
                    registrationClosesAt,
                    status,
                    organizerId,
                    organizerName,
                    eligibleDepartments,
                    minYear,
                    maxYear,
                    allowedTransitions,
                    seatsTaken,
                    seatsLeft,
                    version,
                    sessions,
                    staticQrAllowed,
                    passColor,
                    reentryAllowed,
                    certificateMinSessions,
                    null);
        }

        /** RL1: absent (an event-service from before P8) means the old behaviour, a waitlist. */
        public boolean hasWaitlist() {
            return !Boolean.FALSE.equals(waitlistEnabled);
        }

        public int fillPercent() {
            return percent(seatsTaken, capacity);
        }

        /** Few seats left (a tenth or less): the bar turns amber. */
        public boolean seatsLow() {
            return seatsLeft != null && capacity != null && seatsLeft > 0 && seatsLeft * 10 <= capacity;
        }

        /** "CSE, IT · years 2-4", or "Open to everyone". */
        public String eligibilityText() {
            String departments = eligibleDepartments == null || eligibleDepartments.isEmpty()
                    ? ""
                    : String.join(", ", eligibleDepartments);
            String years;
            if (minYear != null && maxYear != null) {
                years = minYear.equals(maxYear) ? "year " + minYear : "years " + minYear + "-" + maxYear;
            } else if (minYear != null) {
                years = "year " + minYear + " and above";
            } else if (maxYear != null) {
                years = "year " + maxYear + " and below";
            } else {
                years = "";
            }
            if (departments.isEmpty() && years.isEmpty()) {
                return "Open to everyone";
            }
            return departments.isEmpty() ? years : years.isEmpty() ? departments : departments + " · " + years;
        }
    }

    public record Registration(
            UUID id,
            Long eventId,
            String eventCode,
            String eventTitle,
            Instant eventStartsAt,
            String status,
            Integer waitlistPosition,
            Boolean viaPromotion,
            Instant createdAt,
            UUID promotedRegistrationId,
            String userName,
            String userEmail) {

        public boolean isActive() {
            return !"CANCELLED".equals(status);
        }
    }

    public record Eligibility(
            Boolean eligible,
            Boolean registrationOpen,
            List<String> reasons,
            Integer seatsLeft,
            Long waitlistCount,
            Registration myRegistration,
            /* RL1: registration has closed, but the waitlist stays open until LIVE. */
            Boolean waitlistOnly) {

        public Eligibility(
                Boolean eligible,
                Boolean registrationOpen,
                List<String> reasons,
                Integer seatsLeft,
                Long waitlistCount,
                Registration myRegistration) {
            this(eligible, registrationOpen, reasons, seatsLeft, waitlistCount, myRegistration, null);
        }

        public boolean onlyWaitlist() {
            return Boolean.TRUE.equals(waitlistOnly);
        }
    }

    /** Result of a registration; {@code replayed} when the gateway answered from the Idempotency-Key store. */
    public record RegisterResult(Registration registration, boolean replayed) {}

    public record FieldError(String field, String message) {}

    /** A pass card from pass-service (docs/plans/phase-4.md §7). */
    public record Pass(
            UUID id,
            String passNumber,
            String status,
            UUID registrationId,
            Long eventId,
            String eventCode,
            String eventTitle,
            Instant startsAt,
            Instant endsAt,
            String holderName,
            Instant issuedAt,
            Instant codeUnlocksAt,
            Instant revokedAt,
            String revokeReason,
            String revokeNote,
            /** P5: first check-in at a gate; usedAt also set for a single-entry event. */
            Instant checkedInAt,
            Instant usedAt) {

        /** ACTIVE: the live code is shown. */
        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        /** ISSUED or ACTIVE: can still get its holder in (and be revoked). */
        public boolean isValid() {
            return "ISSUED".equals(status) || "ACTIVE".equals(status);
        }
    }

    /** One event in the student's attendance record (checkin-service {@code GET /api/attendance/me}). */
    public record MyAttendance(
            Long eventId,
            String eventCode,
            String eventTitle,
            String passNumber,
            Integer sessionsAttended,
            Integer sessionsTotal,
            Integer sessionsRequired,
            Boolean certificateEligible,
            Instant firstCheckIn) {

        public int progressPercent() {
            return percent(sessionsAttended, sessionsRequired);
        }
    }

    /** The logged-in user's account (auth-service {@code GET /api/users/me}). */
    public record UserProfile(
            UUID id,
            String email,
            String fullName,
            String department,
            Integer year,
            String registerNumber,
            List<String> roles,
            Boolean emailVerified,
            Boolean locked,
            Instant createdAt,
            Instant lastLoginAt,
            /* RL10: an admin banned the account. */
            Boolean banned) {

        public UserProfile(
                UUID id,
                String email,
                String fullName,
                String department,
                Integer year,
                String registerNumber,
                List<String> roles,
                Boolean emailVerified,
                Boolean locked,
                Instant createdAt,
                Instant lastLoginAt) {
            this(
                    id,
                    email,
                    fullName,
                    department,
                    year,
                    registerNumber,
                    roles,
                    emailVerified,
                    locked,
                    createdAt,
                    lastLoginAt,
                    null);
        }

        public boolean isBanned() {
            return Boolean.TRUE.equals(banned);
        }
    }

    /** One registered instance and its health (gateway {@code GET /api/admin/health}, D15). */
    public record InstanceHealth(
            String service,
            String instanceId,
            String url,
            String status,
            java.util.Map<String, String> components,
            String detail,
            Instant checkedAt) {

        public boolean isUp() {
            return "UP".equals(status);
        }
    }

    public record KeyRotation(String kid) {}

    /** Bytes the portal passes through to the browser (QR image, PDF ticket); never a token. */
    public record Binary(byte[] body, String contentType, String contentDisposition) {}

    // ---- dead letters of one consuming service (P7, D13) -----------------------------------------------------------

    public record DeadLetterOverview(List<DeadLetterQueue> queues, List<DeadLetter> messages) {

        public long total() {
            return queues == null
                    ? 0
                    : queues.stream().mapToLong(DeadLetterQueue::messages).sum();
        }
    }

    public record DeadLetterQueue(String inbox, String deadLetterQueue, long messages) {}

    public record DeadLetter(
            String inbox,
            String messageId,
            String type,
            String routingKey,
            long deaths,
            String reason,
            Instant deadLetteredAt,
            int bodyBytes) {}

    // ---- checkin-service: the live dashboard (P7, D16) ----------------------------------------------------------

    /** The snapshot checkin-service pushes over STOMP; the page's first paint uses the same. */
    public record LiveSnapshot(
            long eventId,
            Instant at,
            long confirmed,
            long checkedIn,
            long inside,
            Integer capacity,
            List<LiveGate> gates,
            List<LiveBucket> arrivals,
            List<LiveScan> recent) {

        public long gateMax() {
            return gates == null
                    ? 0
                    : gates.stream().mapToLong(LiveGate::checkedIn).max().orElse(0);
        }

        /** Inside now as a share of the capacity (0–100), for the gauge. */
        public int insidePercent() {
            return capacity == null || capacity == 0 ? 0 : (int) Math.min(100, inside * 100 / capacity);
        }
    }

    public record LiveGate(String code, long checkedIn) {}

    public record LiveBucket(Instant start, long count) {}

    public record LiveScan(Instant at, String firstName, String gateCode, String code, String color) {}

    // ---- Phase 5: staffing, scans, attendance
    // ------------------------------------------------------------------------

    public record Gate(Long id, String code, String name) {}

    /** A volunteer assigned to an event (organizer's view); {@code gate} null = any gate of the event. */
    public record Volunteer(
            UUID volunteerId, String volunteerName, String volunteerEmail, Gate gate, Instant assignedAt) {}

    /** {@code gate} null = any gate of the event. */
    public record Assignment(Long eventId, String eventCode, String eventTitle, Instant eventStartsAt, Gate gate) {}

    public record Attendee(UUID passId, String passNumber, String holderName) {}

    /**
     * checkin-service's answer to a scan: the 200 body, or the same fields from a refusal's ProblemDetail
     * ({@code result}, {@code message}, {@code color}, …). The portal shows both the same way.
     */
    public record ScanAnswer(
            UUID scanId,
            String result,
            String message,
            String color,
            Attendee attendee,
            String eventCode,
            String gateCode,
            Long sessionId,
            String sessionTitle,
            Instant checkedInAt,
            Boolean reentry) {

        public boolean accepted() {
            return "ACCEPTED".equals(result) || "CHECKED_OUT".equals(result);
        }

        /** CSS class: green, amber or red. */
        public String colorClass() {
            return color == null ? "red" : color.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A typed number found for the visual check. */
    public record PassLookup(
            String passNumber,
            String holderName,
            String passStatus,
            String presence,
            String eventCode,
            String gateCode,
            Long sessionId,
            String sessionTitle) {}

    /** Exactly one is set: the pass to confirm, or the refusal. */
    public record LookupAnswer(PassLookup pass, ScanAnswer refusal) {}

    public record SessionCount(Long sessionId, String title, Long checkedIn, Double percent) {}

    public record GateCount(String gateCode, Long checkedIn) {}

    public record AttendanceSummary(
            Long eventId,
            String eventCode,
            String eventTitle,
            Long validPasses,
            Long checkedIn,
            Double percent,
            Long insideNow,
            List<SessionCount> sessions,
            List<GateCount> gates,
            Instant lastScanAt) {}

    public record AttendeeRow(
            UUID passId,
            String passNumber,
            String holderName,
            String holderEmail,
            String passStatus,
            Integer sessionsAttended,
            Instant firstCheckIn,
            String lastGate,
            String presence) {}

    public record CertificateRow(
            String passNumber, String holderName, String holderEmail, Integer sessionsAttended, Integer required) {}

    public record SyncResult(Long eventId, Integer passes, Integer revoked) {}
}
