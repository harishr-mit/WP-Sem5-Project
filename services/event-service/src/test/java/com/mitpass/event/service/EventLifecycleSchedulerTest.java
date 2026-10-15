package com.mitpass.event.service;

import static com.mitpass.event.web.LifecycleTests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.web.dto.StateChangeResponse;
import com.mitpass.security.test.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Time-based moves (docs/plans/phase-3.md §5). The scheduled loop is off in tests; each test calls {@code runOnce(at)}
 * with an explicit time. Fixtures sit near the real "now" (2026), so other test classes' events (2099) are never due.
 */
@EventIntegrationTest
class EventLifecycleSchedulerTest {

    static final AtomicInteger CODES = new AtomicInteger();
    static final String OWNER = TestJwtIssuer.token(Roles.ORGANIZER);

    @Autowired
    MockMvc mvc;

    @Autowired
    EventLifecycleScheduler scheduler;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    EventRepository events;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    void opensRegistrationWhenItsOpeningTimeComes() throws Exception {
        long id = create(now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
        move(id, EventStatus.PUBLISHED);

        scheduler.runOnce(now.plus(Duration.ofMinutes(9)));
        assertThat(statusOf(id)).isEqualTo(EventStatus.PUBLISHED);

        scheduler.runOnce(now.plus(Duration.ofMinutes(10)));
        assertThat(statusOf(id)).isEqualTo(EventStatus.REGISTRATION_OPEN);
        StateChangeResponse last = last(id);
        assertThat(last.trigger()).isEqualTo(TransitionTrigger.SCHEDULER);
        assertThat(last.changedBy()).isEqualTo("system");
    }

    @Test
    void walksAnEventThroughCloseLiveAndCompleted() throws Exception {
        Instant closes = now.plus(Duration.ofHours(1));
        Instant starts = now.plus(Duration.ofHours(3));
        long id = create(now.minus(Duration.ofDays(1)), closes, starts);
        move(id, EventStatus.PUBLISHED);
        move(id, EventStatus.REGISTRATION_OPEN);

        scheduler.runOnce(closes);
        assertThat(statusOf(id)).isEqualTo(EventStatus.REGISTRATION_CLOSED);

        scheduler.runOnce(starts.minus(Duration.ofMinutes(31)));
        assertThat(statusOf(id)).as("doors open 30 min before the start").isEqualTo(EventStatus.REGISTRATION_CLOSED);
        scheduler.runOnce(starts.minus(Duration.ofMinutes(30)));
        assertThat(statusOf(id)).isEqualTo(EventStatus.LIVE);

        scheduler.runOnce(starts.plus(Duration.ofHours(3)));
        assertThat(statusOf(id)).isEqualTo(EventStatus.COMPLETED);
        assertThat(lifecycle.history(id))
                .extracting(StateChangeResponse::trigger)
                .containsExactly(
                        TransitionTrigger.MANUAL, // created
                        TransitionTrigger.MANUAL, // published
                        TransitionTrigger.MANUAL, // opened by hand
                        TransitionTrigger.SCHEDULER,
                        TransitionTrigger.SCHEDULER,
                        TransitionTrigger.SCHEDULER);
    }

    @Test
    void withoutAClosingTimeRegistrationClosesAtTheStart() throws Exception {
        Instant starts = now.plus(Duration.ofHours(2));
        long id = create(now.minus(Duration.ofDays(1)), null, starts);
        move(id, EventStatus.PUBLISHED);
        move(id, EventStatus.REGISTRATION_OPEN);

        scheduler.runOnce(starts.minusSeconds(1));
        assertThat(statusOf(id)).isEqualTo(EventStatus.REGISTRATION_OPEN);

        // At the start it is also past the doors-open time, so one run closes registration and starts the event.
        scheduler.runOnce(starts);
        assertThat(lifecycle.history(id))
                .extracting(StateChangeResponse::toStatus)
                .endsWith(EventStatus.REGISTRATION_CLOSED, EventStatus.LIVE);
    }

    @Test
    void aSecondRunAtTheSameTimeChangesNothing() throws Exception {
        long id = create(now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
        move(id, EventStatus.PUBLISHED);

        scheduler.runOnce(now);
        scheduler.runOnce(now);

        assertThat(lifecycle.history(id)).hasSize(3);
    }

    @Test
    void leavesDraftsAndCancelledEventsAlone() throws Exception {
        long draft =
                create(now.minus(Duration.ofDays(1)), now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));
        long cancelled =
                create(now.minus(Duration.ofDays(1)), now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));
        move(cancelled, EventStatus.PUBLISHED);
        move(cancelled, EventStatus.CANCELLED);

        scheduler.runOnce(now.plus(Duration.ofHours(6)));

        assertThat(statusOf(draft)).isEqualTo(EventStatus.DRAFT);
        assertThat(statusOf(cancelled)).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void doesNotOpenAnEventWhoseRegistrationAlreadyClosed() throws Exception {
        long id = create(now.minus(Duration.ofDays(2)), now.plus(Duration.ofMinutes(5)), now.plus(Duration.ofDays(1)));
        move(id, EventStatus.PUBLISHED);

        scheduler.runOnce(now.plus(Duration.ofMinutes(6)));

        assertThat(statusOf(id)).isEqualTo(EventStatus.PUBLISHED);
    }

    private long create(Instant opens, Instant closes, Instant starts) throws Exception {
        String code = "SCHED" + CODES.incrementAndGet();
        String body = """
                {"code":"%s","title":"Scheduled %s","categoryId":1,"venueId":1,"capacity":10,
                 "startsAt":"%s","endsAt":"%s","registrationOpensAt":"%s"%s}
                """.formatted(
                        code,
                        code,
                        starts,
                        starts.plus(Duration.ofHours(2)),
                        opens,
                        closes == null ? "" : ",\"registrationClosesAt\":\"%s\"".formatted(closes));
        String response = mvc.perform(as(OWNER, post("/api/events")).content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private void move(long id, EventStatus to) {
        lifecycle.transition(id, to, "test", TransitionTrigger.MANUAL, null);
    }

    private EventStatus statusOf(long id) {
        return events.findById(id).orElseThrow().getStatus();
    }

    private StateChangeResponse last(long id) {
        List<StateChangeResponse> all = lifecycle.history(id);
        return all.get(all.size() - 1);
    }
}
