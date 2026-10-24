package com.mitpass.event.domain;

import static com.mitpass.event.domain.EventStatus.CANCELLED;
import static com.mitpass.event.domain.EventStatus.COMPLETED;
import static com.mitpass.event.domain.EventStatus.DRAFT;
import static com.mitpass.event.domain.EventStatus.LIVE;
import static com.mitpass.event.domain.EventStatus.PUBLISHED;
import static com.mitpass.event.domain.EventStatus.REGISTRATION_CLOSED;
import static com.mitpass.event.domain.EventStatus.REGISTRATION_OPEN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The event transition table (PROJECT.md §3, docs/DECISIONS.md D10). */
class EventStatusTest {

    @Test
    void tableMatchesTheProjectPlan() {
        assertThat(DRAFT.allowedNext()).containsExactly(PUBLISHED);
        assertThat(PUBLISHED.allowedNext()).containsExactly(REGISTRATION_OPEN, CANCELLED);
        assertThat(REGISTRATION_OPEN.allowedNext()).containsExactly(REGISTRATION_CLOSED, CANCELLED);
        assertThat(REGISTRATION_CLOSED.allowedNext()).containsExactly(LIVE, CANCELLED);
        assertThat(LIVE.allowedNext()).containsExactly(REGISTRATION_CLOSED, COMPLETED);
        // RL10: only an admin leaves these (un-cancel, reopen); for organizers and the scheduler they stay final.
        assertThat(COMPLETED.allowedNext()).containsExactly(LIVE);
        assertThat(CANCELLED.allowedNext()).containsExactly(PUBLISHED, REGISTRATION_OPEN, REGISTRATION_CLOSED);
        assertThat(COMPLETED.manualNext()).isEmpty();
        assertThat(CANCELLED.manualNext()).isEmpty();
        assertThat(CANCELLED.scheduledNext()).isEmpty();
    }

    @Test
    void organizersNeverGetTheLiveButton() {
        assertThat(REGISTRATION_CLOSED.manualNext()).containsExactly(CANCELLED);
        for (EventStatus status : EventStatus.values()) {
            assertThat(status.manualNext()).doesNotContain(LIVE);
        }
    }

    @Test
    void organizersCanUndoAnEarlyStart() {
        assertThat(LIVE.manualNext()).containsExactly(REGISTRATION_CLOSED, COMPLETED);
    }

    @Test
    void schedulerOnlyMakesTheTimeBasedMoves() {
        assertThat(PUBLISHED.scheduledNext()).containsExactly(REGISTRATION_OPEN);
        assertThat(REGISTRATION_OPEN.scheduledNext()).containsExactly(REGISTRATION_CLOSED);
        assertThat(REGISTRATION_CLOSED.scheduledNext()).containsExactly(LIVE);
        assertThat(LIVE.scheduledNext()).containsExactly(COMPLETED);
        Set<EventStatus> never = EnumSet.of(DRAFT, PUBLISHED, CANCELLED);
        for (EventStatus status : EventStatus.values()) {
            assertThat(status.scheduledNext()).doesNotContainAnyElementsOf(never);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = EventStatus.class,
            names = {"LIVE", "COMPLETED", "CANCELLED"})
    void readOnlyOnceTheEventStarts(EventStatus status) {
        assertThat(status.isEditable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = EventStatus.class,
            names = {"DRAFT", "PUBLISHED", "REGISTRATION_OPEN", "REGISTRATION_CLOSED"})
    void editableBeforeItStarts(EventStatus status) {
        assertThat(status.isEditable()).isTrue();
    }
}
