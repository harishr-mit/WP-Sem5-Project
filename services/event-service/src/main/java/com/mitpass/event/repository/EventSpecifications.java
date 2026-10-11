package com.mitpass.event.repository;

import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.service.EventSearchCriteria;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** One JPA Specification per search filter; {@link #matching} combines the ones a request actually uses. */
public final class EventSpecifications {

    private EventSpecifications() {}

    /** All non-blank filters of {@code criteria}, ANDed. {@code from}/{@code to} are calendar days in {@code zone}. */
    public static Specification<Event> matching(EventSearchCriteria criteria, ZoneId zone) {
        List<Specification<Event>> specs = new ArrayList<>();
        if (hasText(criteria.q())) {
            specs.add(textContains(criteria.q()));
        }
        if (hasText(criteria.category())) {
            specs.add(inCategory(criteria.category()));
        }
        if (hasText(criteria.department())) {
            specs.add(inDepartment(criteria.department()));
        }
        if (criteria.from() != null) {
            specs.add(startsOnOrAfter(criteria.from().atStartOfDay(zone).toInstant()));
        }
        if (criteria.to() != null) {
            specs.add(startsBefore(criteria.to().plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if (criteria.status() != null) {
            specs.add(hasStatus(criteria.status()));
        }
        if (criteria.organizerId() != null) {
            specs.add(organizedBy(criteria.organizerId()));
        }
        return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    }

    /** Case-insensitive substring of title or description; {@code %} and {@code _} in the input are literal. */
    public static Specification<Event> textContains(String text) {
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'));
    }

    public static Specification<Event> inCategory(String categoryCode) {
        String code = categoryCode.trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(root.get("category").get("code"), code);
    }

    public static Specification<Event> inDepartment(String department) {
        String dept = department.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("department")), dept);
    }

    public static Specification<Event> startsOnOrAfter(Instant instant) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), instant);
    }

    public static Specification<Event> startsBefore(Instant instant) {
        return (root, query, cb) -> cb.lessThan(root.get("startsAt"), instant);
    }

    public static Specification<Event> organizedBy(UUID organizerId) {
        return (root, query, cb) -> cb.equal(root.get("organizerId"), organizerId);
    }

    public static Specification<Event> hasStatus(EventStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
