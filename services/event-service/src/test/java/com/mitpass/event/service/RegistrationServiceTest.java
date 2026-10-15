package com.mitpass.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.IllegalTransitionException;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.RegistrationStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.RegistrationRepository;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import com.mitpass.event.web.dto.RegistrationResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** Seats, waitlist and promotion on a real PostgreSQL (docs/plans/phase-3.md §8). */
@EventIntegrationTest
class RegistrationServiceTest {

    static final AtomicInteger CODES = new AtomicInteger();

    @Autowired
    RegistrationService registrations;

    @Autowired
    EventService eventService;

    @Autowired
    EventLifecycleService lifecycle;

    @Autowired
    EventRepository events;

    @Autowired
    RegistrationRepository registrationRepository;

    @Test
    void confirmsWhileSeatsAreLeftThenWaitlistsInOrder() {
        long event = openEvent(2);

        assertThat(registrations.register(student(), event).status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registrations.register(student(), event).status()).isEqualTo(RegistrationStatus.CONFIRMED);
        RegistrationResponse third = registrations.register(student(), event);
        RegistrationResponse fourth = registrations.register(student(), event);

        assertThat(third.status()).isEqualTo(RegistrationStatus.WAITLISTED);
        assertThat(third.waitlistPosition()).isEqualTo(1);
        assertThat(fourth.waitlistPosition()).isEqualTo(2);
        assertThat(seatsTaken(event)).isEqualTo(2);
        assertThat(eventService.get(event).seatsLeft()).isZero();
    }

    @Test
    void cancellingAConfirmedSeatPromotesTheFirstWaitlistedStudentInTheSameTransaction() {
        long event = openEvent(1);
        RegistrationResponse first = registrations.register(student(), event);
        RegistrationResponse second = registrations.register(student(), event);
        RegistrationResponse third = registrations.register(student(), event);

        RegistrationResponse cancelled = registrations.cancel(first.id(), "student", null);

        assertThat(cancelled.status()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(cancelled.promotedRegistrationId()).isEqualTo(second.id());
        RegistrationResponse promoted = registrations.get(second.id());
        assertThat(promoted.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(promoted.viaPromotion()).isTrue();
        assertThat(promoted.waitlistPosition()).isNull();
        assertThat(registrations.get(third.id()).waitlistPosition()).isEqualTo(1);
        assertThat(seatsTaken(event)).as("the seat moved, it wasn't freed").isEqualTo(1);
    }

    @Test
    void cancellingWithNobodyWaitingFreesTheSeat() {
        long event = openEvent(3);
        RegistrationResponse only = registrations.register(student(), event);

        RegistrationResponse cancelled = registrations.cancel(only.id(), "student", "Can't make it");

        assertThat(cancelled.promotedRegistrationId()).isNull();
        assertThat(seatsTaken(event)).isZero();
    }

    @Test
    void cancellingAWaitlistedRegistrationMovesTheOthersUp() {
        long event = openEvent(1);
        registrations.register(student(), event);
        RegistrationResponse w1 = registrations.register(student(), event);
        RegistrationResponse w2 = registrations.register(student(), event);

        registrations.cancel(w1.id(), "student", null);

        assertThat(registrations.get(w2.id()).waitlistPosition()).isEqualTo(1);
        assertThat(seatsTaken(event)).isEqualTo(1);
    }

    @Test
    void oneActiveRegistrationPerStudentButReRegisteringAfterCancellingIsAllowed() {
        long event = openEvent(5);
        CurrentUser student = student();
        RegistrationResponse first = registrations.register(student, event);

        assertThatThrownBy(() -> registrations.register(student, event))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).isEqualTo("urn:mitpass:problem:already-registered"));

        registrations.cancel(first.id(), student.id().toString(), null);
        RegistrationResponse again = registrations.register(student, event);

        assertThat(again.id()).isNotEqualTo(first.id());
        assertThat(again.status()).isEqualTo(RegistrationStatus.CONFIRMED);
    }

    @Test
    void cancellingTwiceIsAnIllegalTransition() {
        long event = openEvent(5);
        RegistrationResponse registration = registrations.register(student(), event);
        registrations.cancel(registration.id(), "student", null);

        assertThatThrownBy(() -> registrations.cancel(registration.id(), "student", null))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.getCurrentState()).isEqualTo("CANCELLED");
                    assertThat(ex.getAllowedStates()).isEmpty();
                });
    }

    @Test
    void registrationNeedsAnOpenEvent() {
        long event = createEvent(5, List.of(), null, null);
        lifecycle.transition(event, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);

        assertThatThrownBy(() -> registrations.register(student(), event))
                .isInstanceOfSatisfying(ProblemException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getType().toString()).isEqualTo("urn:mitpass:problem:registration-closed");
                });
    }

    @Test
    void ineligibleStudentsGetTheReasons() {
        long event = createEvent(5, List.of("CSE"), 2, 4);
        open(event);

        assertThatThrownBy(() -> registrations.register(student("AERO", 1), event))
                .isInstanceOfSatisfying(ProblemException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getType().toString()).isEqualTo("urn:mitpass:problem:not-eligible");
                    assertThat((List<?>) ex.getProperties().get("reasons")).hasSize(2);
                });
        assertThat(registrations.register(student("CSE", 3), event).status()).isEqualTo(RegistrationStatus.CONFIRMED);
    }

    @Test
    void cancellationsCloseWhenTheEventGoesLive() {
        long event = openEvent(5);
        RegistrationResponse registration = registrations.register(student(), event);
        lifecycle.transition(event, EventStatus.REGISTRATION_CLOSED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(event, EventStatus.LIVE, "system", TransitionTrigger.SCHEDULER, null);

        assertThatThrownBy(() -> registrations.cancel(registration.id(), "student", null))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).isEqualTo("urn:mitpass:problem:registration-closed"));
    }

    @Test
    void aClosedEventStillPromotesFromTheWaitlist() {
        long event = openEvent(1);
        RegistrationResponse first = registrations.register(student(), event);
        RegistrationResponse waiting = registrations.register(student(), event);
        lifecycle.transition(event, EventStatus.REGISTRATION_CLOSED, "test", TransitionTrigger.MANUAL, null);

        registrations.cancel(first.id(), "student", null);

        assertThat(registrations.get(waiting.id()).status()).isEqualTo(RegistrationStatus.CONFIRMED);
    }

    @Test
    void raisingCapacityPromotesThatManyFromTheWaitlist() {
        long event = openEvent(1);
        registrations.register(student(), event);
        RegistrationResponse w1 = registrations.register(student(), event);
        RegistrationResponse w2 = registrations.register(student(), event);
        RegistrationResponse w3 = registrations.register(student(), event);

        eventService.update(event, update(event, 3));

        assertThat(registrations.get(w1.id()).status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registrations.get(w2.id()).status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registrations.get(w3.id()).waitlistPosition()).isEqualTo(1);
        assertThat(seatsTaken(event)).isEqualTo(3);
    }

    @Test
    void capacityCannotDropBelowTheSeatsTaken() {
        long event = openEvent(3);
        registrations.register(student(), event);
        registrations.register(student(), event);

        assertThatThrownBy(() -> eventService.update(event, update(event, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("2 seats are already taken");
    }

    @Test
    void organizerListsTheEventsRegistrationsByStatus() {
        long event = openEvent(1);
        registrations.register(student(), event);
        registrations.register(student(), event);

        assertThat(registrations
                        .forEvent(event, RegistrationStatus.WAITLISTED, PageRequest.of(0, 10))
                        .totalElements())
                .isEqualTo(1);
        assertThat(registrations.forEvent(event, null, PageRequest.of(0, 10)).totalElements())
                .isEqualTo(2);
    }

    @Test
    void historyRecordsEveryStepIncludingThePromotion() {
        long event = openEvent(1);
        RegistrationResponse first = registrations.register(student(), event);
        RegistrationResponse second = registrations.register(student(), event);
        registrations.cancel(first.id(), "student", null);

        assertThat(registrationRepository.findHistory(second.id()))
                .extracting(change -> change.getToStatus().name() + "/" + change.getTrigger())
                .containsExactly("PENDING/MANUAL", "WAITLISTED/MANUAL", "CONFIRMED/SYSTEM");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    private long openEvent(int capacity) {
        long id = createEvent(capacity, List.of(), null, null);
        open(id);
        return id;
    }

    private void open(long id) {
        lifecycle.transition(id, EventStatus.PUBLISHED, "test", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "test", TransitionTrigger.MANUAL, null);
    }

    private long createEvent(int capacity, List<String> departments, Integer minYear, Integer maxYear) {
        EventDetailResponse created = eventService.create(
                TestData.ORGANIZER_USER,
                TestData.createRequest("REG" + CODES.incrementAndGet(), capacity, departments, minYear, maxYear));
        return created.id();
    }

    private EventUpdateRequest update(long id, int capacity) {
        EventDetailResponse current = eventService.get(id);
        return new EventUpdateRequest(
                current.code(),
                current.title(),
                current.description(),
                current.category().id(),
                current.venue().id(),
                current.department(),
                capacity,
                current.startsAt(),
                current.endsAt(),
                current.registrationOpensAt(),
                current.registrationClosesAt(),
                current.version(),
                current.eligibleDepartments(),
                current.minYear(),
                current.maxYear());
    }

    private int seatsTaken(long event) {
        return events.findSeatsTakenById(event);
    }

    static CurrentUser student() {
        return student("CSE", 2);
    }

    static CurrentUser student(String department, Integer year) {
        UUID id = UUID.randomUUID();
        return new CurrentUser(
                id,
                "s-" + id.toString().substring(0, 8) + "@student.test",
                "Student " + id.toString().substring(0, 4),
                Set.of("STUDENT"),
                department,
                year,
                null,
                "token");
    }
}
