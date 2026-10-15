package com.mitpass.event.idempotency;

import static com.mitpass.event.web.LifecycleTests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.service.EventService;
import com.mitpass.event.service.RegistrationService;
import com.mitpass.event.service.RegistrationService.RegisterOutcome;
import com.mitpass.event.web.RegistrationApiTests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Idempotency-Key on POST /api/registrations (docs/plans/phase-3.md §4, exit criterion 5). */
@EventIntegrationTest
class IdempotencyTests {

    static final AtomicInteger CODES = new AtomicInteger();

    @Autowired
    RegistrationService registrations;

    @Autowired
    IdempotencyStore store;

    @Autowired
    EventService events;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    long eventId;

    @BeforeEach
    void openEvent() {
        eventId = openEvent(5, List.of());
    }

    @Test
    void retryWithTheSameKeyReplaysTheOriginalResult() {
        CurrentUser student = student();

        RegisterOutcome first = registrations.register(student, eventId, "key-1");
        RegisterOutcome retry = registrations.register(student, eventId, "key-1");

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.response()).isEqualTo(first.response());
        assertThat(activeRegistrations(student.id())).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRejected() {
        CurrentUser student = student();
        registrations.register(student, eventId, "key-2");
        long other = openEvent(5, List.of());

        assertThatThrownBy(() -> registrations.register(student, other, "key-2"))
                .isInstanceOfSatisfying(ProblemException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getType().toString()).isEqualTo("urn:mitpass:problem:idempotency-key-mismatch");
                });
    }

    @Test
    void keysAreScopedPerUser() {
        RegisterOutcome a = registrations.register(student(), eventId, "shared-key");
        RegisterOutcome b = registrations.register(student(), eventId, "shared-key");

        assertThat(b.replayed()).isFalse();
        assertThat(b.response().id()).isNotEqualTo(a.response().id());
    }

    @Test
    void aFailedRequestLeavesNoKeySoARetryRunsAgain() {
        long cseOnly = openEvent(5, List.of("CSE"));
        CurrentUser aero =
                new CurrentUser(UUID.randomUUID(), "a@test", "Aero", Set.of("STUDENT"), "AERO", 1, null, "t");

        assertThatThrownBy(() -> registrations.register(aero, cseOnly, "key-fail"))
                .isInstanceOf(ProblemException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE idem_key = 'key-fail'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void twoConcurrentRetriesRegisterOnce() throws Exception {
        CurrentUser student = student();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<RegisterOutcome>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return registrations.register(student, eventId, "key-race");
            }));
        }
        start.countDown();

        List<RegisterOutcome> outcomes = new ArrayList<>();
        for (CompletableFuture<RegisterOutcome> call : calls) {
            outcomes.add(call.get());
        }
        assertThat(outcomes)
                .extracting(o -> o.response().id())
                .containsOnly(outcomes.get(0).response().id());
        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(activeRegistrations(student.id())).isEqualTo(1);
    }

    @Test
    void expiredKeysAreCleanedUp() {
        CurrentUser student = student();
        registrations.register(student, eventId, "key-old");

        assertThat(store.deleteExpired(Instant.now())).isZero();
        assertThat(store.deleteExpired(Instant.now().plus(Duration.ofHours(25))))
                .isPositive();
    }

    @Test
    void overHttpTheReplayIsMarkedAndBadKeysAre400() throws Exception {
        String token = RegistrationApiTests.student("CSE", 2);
        String body = "{\"eventId\":%d}".formatted(eventId);

        String first = mvc.perform(as(token, post("/api/registrations"))
                        .header("Idempotency-Key", "http-key-1")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(first, "$.id");

        mvc.perform(as(token, post("/api/registrations"))
                        .header("Idempotency-Key", "http-key-1")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(id)))
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(as(token, post("/api/registrations")).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:already-registered"));

        mvc.perform(as(token, post("/api/registrations"))
                        .header("Idempotency-Key", "not ok!")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private long openEvent(int capacity, List<String> departments) {
        long id = events.create(
                        TestData.ORGANIZER_USER,
                        TestData.createRequest("IDEM" + CODES.incrementAndGet(), capacity, departments, null, null))
                .id();
        lifecycle.transition(id, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
        return id;
    }

    private long activeRegistrations(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM registration WHERE user_id = :u AND status <> 'CANCELLED'")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    private static CurrentUser student() {
        UUID id = UUID.randomUUID();
        return new CurrentUser(id, id + "@student.test", "Student", Set.of("STUDENT"), "CSE", 2, null, "token");
    }
}
