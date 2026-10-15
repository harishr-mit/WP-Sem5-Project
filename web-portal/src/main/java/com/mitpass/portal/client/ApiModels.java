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
            UUID promotedRegistrationId) {

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
}
