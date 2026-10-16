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
            Integer seatsLeft) {}

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
            Integer seatsLeft) {

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
            Registration myRegistration) {}

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

    /** Bytes the portal passes through to the browser (QR image, PDF ticket); never a token. */
    public record Binary(byte[] body, String contentType, String contentDisposition) {}

    // ---- Phase 5: staffing, scans, attendance
    // ------------------------------------------------------------------------

    public record Gate(Long id, String code, String name) {}

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
