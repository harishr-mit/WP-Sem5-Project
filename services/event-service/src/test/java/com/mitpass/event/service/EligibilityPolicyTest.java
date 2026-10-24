package com.mitpass.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.TestData;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.Venue;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Who may register, and when (docs/plans/phase-3.md §4 "Eligibility"). Pure domain logic, no Spring. */
class EligibilityPolicyTest {

    static final Instant NOW = Instant.parse("2099-11-01T00:00:00Z");
    static final Instant START = Instant.parse("2099-11-14T03:30:00Z");

    final EligibilityPolicy policy = new EligibilityPolicy();
    Event event;

    @BeforeEach
    void openEvent() throws Exception {
        event = TestData.event(
                "ELIG1", new Category("TECHNICAL", "Technical"), new Venue("Lab", "B", 100, null), START);
        event.setRegistrationClosesAt(START.minusSeconds(86_400));
        setStatus(EventStatus.REGISTRATION_OPEN);
    }

    @Test
    void anyoneMayRegisterForAnOpenEventWithoutRestrictions() {
        Eligibility result = policy.check(event, student("AERO", 1), NOW);

        assertThat(result.eligible()).isTrue();
        assertThat(result.registrationOpen()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void departmentMustBeListed() {
        event.setEligibleDepartments(List.of("IT", "CSE"));

        assertThat(policy.check(event, student("CSE", 1), NOW).eligible()).isTrue();
        Eligibility aero = policy.check(event, student("AERO", 1), NOW);
        assertThat(aero.eligible()).isFalse();
        assertThat(aero.registrationOpen()).isTrue();
        assertThat(aero.reasons()).containsExactly("Open to CSE, IT only (you are in AERO)");
    }

    @Test
    void yearMustBeInRange() {
        event.setMinYear(2);
        event.setMaxYear(4);

        assertThat(policy.check(event, student("CSE", 2), NOW).eligible()).isTrue();
        assertThat(policy.check(event, student("CSE", 4), NOW).eligible()).isTrue();
        assertThat(policy.check(event, student("CSE", 1), NOW).reasons())
                .containsExactly("Open to years 2-4 only (you are in year 1)");
        assertThat(policy.check(event, student("CSE", 5), NOW).eligible()).isFalse();
    }

    @Test
    void oneSidedYearLimits() {
        event.setMinYear(3);
        assertThat(policy.check(event, student("CSE", 2), NOW).reasons())
                .containsExactly("Open to year 3 and above only (you are in year 2)");

        event.setMinYear(null);
        event.setMaxYear(1);
        assertThat(policy.check(event, student("CSE", 2), NOW).reasons())
                .containsExactly("Open to year 1 and below only (you are in year 2)");
    }

    @Test
    void accountsWithoutADepartmentOrYearFailARestriction() {
        event.setEligibleDepartments(List.of("CSE"));
        event.setMinYear(2);

        Eligibility result = policy.check(event, student(null, null), NOW);

        assertThat(result.reasons())
                .containsExactly(
                        "Open to CSE only (your account has no department)",
                        "Open to year 2 and above only (your account has no year of study)");
    }

    @Test
    void registrationMustBeOpen() throws Exception {
        setStatus(EventStatus.PUBLISHED);

        Eligibility result = policy.check(event, student("CSE", 1), NOW);

        assertThat(result.eligible()).isFalse();
        assertThat(result.registrationOpen()).isFalse();
        assertThat(result.reasons()).containsExactly("Registration is not open (the event is PUBLISHED)");
    }

    @Test
    void closingTimeCountsEvenBeforeTheSchedulerRuns() {
        event.setWaitlistEnabled(false);
        Instant afterClose = event.getRegistrationClosesAt();

        Eligibility result = policy.check(event, student("CSE", 1), afterClose);

        assertThat(result.registrationOpen()).isFalse();
        assertThat(result.reasons().get(0)).startsWith("Registration closed at");
    }

    @Test
    void withoutAClosingTimeRegistrationEndsAtTheStart() {
        event.setWaitlistEnabled(false);
        event.setRegistrationClosesAt(null);

        assertThat(policy.check(event, student("CSE", 1), START.minusSeconds(1)).eligible())
                .isTrue();
        assertThat(policy.check(event, student("CSE", 1), START).registrationOpen())
                .isFalse();
    }

    @Test
    void withAWaitlistClosingLeavesTheWaitlistOpenUntilLive() throws Exception {
        event.setWaitlistEnabled(true);
        Eligibility afterTime = policy.check(event, student("CSE", 1), event.getRegistrationClosesAt());
        assertThat(afterTime.eligible()).isTrue();
        assertThat(afterTime.waitlistOnly()).isTrue();

        setStatus(EventStatus.REGISTRATION_CLOSED);
        assertThat(policy.check(event, student("CSE", 1), START.minusSeconds(60))
                        .waitlistOnly())
                .isTrue();

        setStatus(EventStatus.LIVE);
        Eligibility live = policy.check(event, student("CSE", 1), START);
        assertThat(live.registrationOpen()).isFalse();
        assertThat(live.waitlistOnly()).isFalse();
    }

    @Test
    void allReasonsAreReportedTogether() throws Exception {
        setStatus(EventStatus.PUBLISHED);
        event.setEligibleDepartments(List.of("CSE"));
        event.setMaxYear(1);

        assertThat(policy.check(event, student("ECE", 3), NOW).reasons()).hasSize(3);
    }

    static CurrentUser student(String department, Integer year) {
        return new CurrentUser(
                UUID.randomUUID(), "s@test", "Student", Set.of("STUDENT"), department, year, null, "token");
    }

    private void setStatus(EventStatus status) throws Exception {
        Field field = Event.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(event, status);
    }
}
