package com.mitpass.common.web.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.common.web.error.IllegalTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LifecycleStateTest {

    /** A small machine shaped like the real ones: a happy path, a side exit and a terminal state. */
    enum Door implements LifecycleState<Door> {
        CLOSED,
        OPEN,
        LOCKED,
        REMOVED;

        private static final Map<Door, Set<Door>> NEXT = new EnumMap<>(Door.class);

        static {
            NEXT.put(CLOSED, EnumSet.of(OPEN, LOCKED, REMOVED));
            NEXT.put(OPEN, EnumSet.of(CLOSED));
            NEXT.put(LOCKED, EnumSet.of(CLOSED));
            NEXT.put(REMOVED, EnumSet.noneOf(Door.class));
        }

        @Override
        public Set<Door> allowedNext() {
            return NEXT.get(this);
        }
    }

    @Test
    void allowedMovesPass() {
        LifecycleState.require(Door.CLOSED, Door.OPEN);
        LifecycleState.require(Door.OPEN, Door.CLOSED);
        assertThat(Door.CLOSED.canMoveTo(Door.LOCKED)).isTrue();
    }

    @Test
    void illegalMoveThrowsWithTheAllowedStatesInTableOrder() {
        assertThatThrownBy(() -> LifecycleState.require(Door.OPEN, Door.LOCKED))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCurrentState()).isEqualTo("OPEN");
                    assertThat(ex.getRequestedState()).isEqualTo("LOCKED");
                    assertThat(ex.getAllowedStates()).containsExactly("CLOSED");
                    assertThat(ex.getMessage()).contains("OPEN", "LOCKED", "CLOSED");
                });
    }

    @Test
    void terminalStateAllowsNothing() {
        assertThatThrownBy(() -> LifecycleState.require(Door.REMOVED, Door.CLOSED))
                .isInstanceOfSatisfying(IllegalTransitionException.class, ex -> {
                    assertThat(ex.getAllowedStates()).isEmpty();
                    assertThat(ex.getMessage()).contains("final");
                });
    }

    @Test
    void sameStateIsNotAMove() {
        assertThatThrownBy(() -> LifecycleState.require(Door.CLOSED, Door.CLOSED))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void callerSpecificAllowedSetNarrowsTheTable() {
        // E.g. organizers may not press LIVE although the table allows it (the scheduler does).
        Set<Door> forCaller = EnumSet.of(Door.OPEN);
        assertThatThrownBy(() -> LifecycleState.require(Door.CLOSED, Door.LOCKED, forCaller))
                .isInstanceOfSatisfying(
                        IllegalTransitionException.class,
                        ex -> assertThat(ex.getAllowedStates()).containsExactly("OPEN"));
        LifecycleState.require(Door.CLOSED, Door.OPEN, forCaller);
    }
}
