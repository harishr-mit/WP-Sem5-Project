package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Who may register for an event, and when (docs/plans/phase-3.md §4, docs/DECISIONS.md D10). Used by
 * {@code POST /api/registrations} and {@code GET /api/events/{id}/eligibility}, so a student sees the same reasons
 * before and after clicking. Pure logic: the caller supplies the time.
 */
@Component
public class EligibilityPolicy {

    public Eligibility check(Event event, CurrentUser student, Instant now) {
        List<String> reasons = new ArrayList<>();

        boolean open = true;
        if (event.getStatus() != EventStatus.REGISTRATION_OPEN) {
            open = false;
            reasons.add("Registration is not open (the event is %s)".formatted(event.getStatus()));
        } else if (!now.isBefore(event.registrationEndsAt())) {
            // The status is the gate, but the scheduler runs every 30 s: the closing time counts right away.
            open = false;
            reasons.add("Registration closed at %s".formatted(event.registrationEndsAt()));
        }

        List<String> departments = event.getEligibleDepartments();
        // Null-safe: immutable lists throw on contains(null), and a token may carry no department.
        boolean inDepartment = student.department() != null && departments.contains(student.department());
        if (!departments.isEmpty() && !inDepartment) {
            String you = student.department() == null
                    ? "your account has no department"
                    : "you are in " + student.department();
            reasons.add("Open to %s only (%s)".formatted(String.join(", ", departments), you));
        }

        Integer min = event.getMinYear();
        Integer max = event.getMaxYear();
        if (min != null || max != null) {
            Integer year = student.year();
            boolean inRange = year != null && (min == null || year >= min) && (max == null || year <= max);
            if (!inRange) {
                String you = year == null ? "your account has no year of study" : "you are in year " + year;
                reasons.add("Open to %s only (%s)".formatted(describeYears(min, max), you));
            }
        }

        return new Eligibility(reasons.isEmpty(), open, reasons);
    }

    private static String describeYears(Integer min, Integer max) {
        if (min != null && max != null) {
            return min.equals(max) ? "year " + min : "years %d-%d".formatted(min, max);
        }
        return min != null ? "year %d and above".formatted(min) : "year %d and below".formatted(max);
    }
}
