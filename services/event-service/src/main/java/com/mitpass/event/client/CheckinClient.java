package com.mitpass.event.client;

import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks checkin-service, with event-service's own SERVICE token (D7), whether anyone has checked in to an event yet:
 * an organizer can't undo LIVE after that (RL3, D17). checkin-service owns attendance, so it is asked, not copied.
 */
public class CheckinClient {

    private static final Logger log = LoggerFactory.getLogger(CheckinClient.class);

    /** The field of checkin-service's {@code AttendanceSummary} this needs. */
    record Summary(long checkedIn) {}

    private final RestClient client;

    public CheckinClient(RestClient client) {
        this.client = client;
    }

    /** @throws ProblemException 503 if checkin-service can't answer: the undo is refused rather than risked */
    public boolean hasCheckIns(long eventId) {
        try {
            Summary summary = client.get()
                    .uri("/api/attendance/events/{id}/summary", eventId)
                    .retrieve()
                    .body(Summary.class);
            return summary != null && summary.checkedIn() > 0;
        } catch (HttpClientErrorException.NotFound ex) {
            return false; // checkin-service has never heard of the event, so nobody can have checked in
        } catch (RestClientException ex) {
            log.warn("checkin-service unavailable: {}", ex.getMessage());
            throw new ProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemTypes.of("dependency-unavailable"),
                    "Service unavailable",
                    "Could not ask checkin-service whether anyone has checked in. Try again in a moment.");
        }
    }
}
