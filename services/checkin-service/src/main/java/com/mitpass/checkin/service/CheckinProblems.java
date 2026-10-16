package com.mitpass.checkin.service;

import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import org.springframework.http.HttpStatus;

/** checkin-service problem types outside the scan results (docs/plans/phase-5.md §7). */
public final class CheckinProblems {

    private CheckinProblems() {}

    public static ProblemException eventServiceUnavailable() {
        return new ProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.of("event-service-unavailable"),
                "Event service unavailable",
                "Event details or staffing could not be read right now. Try again in a moment.");
    }

    public static ProblemException passServiceUnavailable() {
        return new ProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.of("pass-service-unavailable"),
                "Pass service unavailable",
                "Passes could not be synced from pass-service right now. The automatic sync retries by itself.");
    }
}
