package com.mitpass.checkin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.domain.SessionResolver.Resolution;
import com.mitpass.common.web.error.IllegalTransitionException;
import com.mitpass.common.web.state.LifecycleState;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Presence state machine, result codes and session choice (docs/plans/phase-5.md §4, §5). */
class DomainRulesTest {

    static final Instant DAY = Instant.parse("2026-12-05T04:30:00Z"); // 10:00 IST
    static final Duration DOORS = Duration.ofMinutes(30);

    @Test
    void presenceMovesNotArrivedToInAndInOutBackAndForth() {
        assertThat(PresenceState.NOT_ARRIVED.allowedNext()).containsExactly(PresenceState.IN);
        assertThat(PresenceState.IN.allowedNext()).containsExactly(PresenceState.OUT);
        assertThat(PresenceState.OUT.allowedNext()).containsExactly(PresenceState.IN);
        assertThatThrownBy(() -> LifecycleState.require(PresenceState.NOT_ARRIVED, PresenceState.OUT))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> LifecycleState.require(PresenceState.IN, PresenceState.IN))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void everyResultHasStatusColourAndMessage() {
        for (ScanResult result : ScanResult.values()) {
            assertThat(result.status()).as(result.name()).isNotNull();
            assertThat(result.color()).as(result.name()).isNotNull();
            assertThat(result.message(java.util.Map.of())).as(result.name()).isNotBlank();
        }
        assertThat(EnumSet.allOf(ScanResult.class)).hasSize(15);
        assertThat(ScanResult.ACCEPTED.accepted()).isTrue();
        assertThat(ScanResult.CHECKED_OUT.accepted()).isTrue();
        assertThat(ScanResult.ALREADY_CHECKED_IN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ScanResult.ALREADY_CHECKED_IN.color()).isEqualTo(ScanResult.Color.AMBER);
        assertThat(ScanResult.INVALID_SIGNATURE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ScanResult.UNKNOWN_PASS.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ScanResult.UNAUTHORIZED_SCANNER.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ScanResult.ACCEPTED.message(java.util.Map.of("name", "Priya Raman")))
                .isEqualTo("Welcome, Priya Raman.");
        assertThat(ScanResult.ALREADY_CHECKED_IN.problemSlug()).isEqualTo("scan/already-checked-in");
    }

    @Test
    void anEventWithoutSessionsIsOneImplicitSession() {
        Resolution r = SessionResolver.resolve(List.of(), DAY, DAY.plusSeconds(3600), DAY, DOORS);
        assertThat(r.open()).isTrue();
        assertThat(r.sessionId()).isZero();
        assertThat(r.title()).isNull();
    }

    @Test
    void doorsOpenBeforeASessionAndTheRunningSessionWins() {
        SessionInfo s1 = new SessionInfo(1L, "Data preparation", DAY, DAY.plus(Duration.ofHours(2)));
        SessionInfo s2 =
                new SessionInfo(2L, "Model training", DAY.plus(Duration.ofHours(3)), DAY.plus(Duration.ofHours(5)));
        SessionInfo s3 = new SessionInfo(
                3L, "Deployment", DAY.plus(Duration.ofMinutes(315)), DAY.plus(Duration.ofHours(7))); // 15:15 IST
        List<SessionInfo> sessions = List.of(s3, s1, s2); // order doesn't matter

        assertThat(resolve(sessions, DAY.minus(Duration.ofMinutes(31))).state())
                .isEqualTo(SessionResolver.State.NOT_YET_OPEN);
        assertThat(resolve(sessions, DAY.minus(Duration.ofMinutes(31))).opensAt())
                .isEqualTo(DAY.minus(DOORS));
        assertThat(resolve(sessions, DAY.minus(Duration.ofMinutes(30))).sessionId())
                .isEqualTo(1L);
        assertThat(resolve(sessions, DAY.plus(Duration.ofMinutes(140))).state())
                .as("between sessions 1 and 2, before doors")
                .isEqualTo(SessionResolver.State.NOT_YET_OPEN);
        assertThat(resolve(sessions, DAY.plus(Duration.ofMinutes(155))).sessionId())
                .as("doors for session 2")
                .isEqualTo(2L);
        // 14:50 IST: session 2 runs until 15:00, session 3's doors opened at 14:45. The running session wins.
        assertThat(resolve(sessions, DAY.plus(Duration.ofMinutes(290))).sessionId())
                .isEqualTo(2L);
        assertThat(resolve(sessions, DAY.plus(Duration.ofMinutes(300))).sessionId())
                .isEqualTo(3L);
        assertThat(resolve(sessions, DAY.plus(Duration.ofHours(7))).state()).isEqualTo(SessionResolver.State.CLOSED);
    }

    private static Resolution resolve(List<SessionInfo> sessions, Instant now) {
        return SessionResolver.resolve(sessions, DAY, DAY.plus(Duration.ofHours(7)), now, DOORS);
    }
}
