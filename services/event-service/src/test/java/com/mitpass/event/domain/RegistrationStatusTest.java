package com.mitpass.event.domain;

import static com.mitpass.event.domain.RegistrationStatus.CANCELLED;
import static com.mitpass.event.domain.RegistrationStatus.CONFIRMED;
import static com.mitpass.event.domain.RegistrationStatus.PENDING;
import static com.mitpass.event.domain.RegistrationStatus.WAITLISTED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The registration transition table (PROJECT.md §3). */
class RegistrationStatusTest {

    @Test
    void tableMatchesTheProjectPlan() {
        assertThat(PENDING.allowedNext()).containsExactly(CONFIRMED, WAITLISTED);
        assertThat(WAITLISTED.allowedNext()).containsExactly(CONFIRMED, CANCELLED);
        assertThat(CONFIRMED.allowedNext()).containsExactly(CANCELLED);
        assertThat(CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void onlyActiveRegistrationsCountAgainstTheOnePerEventRule() {
        assertThat(PENDING.isActive()).isTrue();
        assertThat(CONFIRMED.isActive()).isTrue();
        assertThat(WAITLISTED.isActive()).isTrue();
        assertThat(CANCELLED.isActive()).isFalse();
    }
}
