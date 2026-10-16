package com.mitpass.pass.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.common.web.error.IllegalTransitionException;
import com.mitpass.common.web.state.LifecycleState;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Pass table of PROJECT.md §3 / D12, cell by cell. */
class PassStatusTest {

    @Test
    void allowedMoves() {
        assertThat(PassStatus.ISSUED.allowedNext())
                .containsExactlyInAnyOrder(PassStatus.ACTIVE, PassStatus.REVOKED, PassStatus.EXPIRED);
        assertThat(PassStatus.ACTIVE.allowedNext()).containsExactlyInAnyOrder(PassStatus.EXPIRED, PassStatus.REVOKED);
        assertThat(PassStatus.EXPIRED.allowedNext()).isEmpty();
        assertThat(PassStatus.REVOKED.allowedNext()).isEmpty();
    }

    @Test
    void everyOtherPairIsRefusedWithTheAllowedStates() {
        for (PassStatus from : PassStatus.values()) {
            Set<PassStatus> allowed = from.allowedNext();
            for (PassStatus to : EnumSet.complementOf(EnumSet.copyOf(allowedOrEmpty(allowed)))) {
                assertThatThrownBy(() -> LifecycleState.require(from, to))
                        .isInstanceOf(IllegalTransitionException.class)
                        .satisfies(ex -> assertThat(((IllegalTransitionException) ex).getProperties())
                                .containsEntry("currentState", from.name()));
            }
        }
    }

    @Test
    void onlyIssuedAndActiveAreValid() {
        assertThat(PassStatus.ISSUED.isValid()).isTrue();
        assertThat(PassStatus.ACTIVE.isValid()).isTrue();
        assertThat(PassStatus.EXPIRED.isValid()).isFalse();
        assertThat(PassStatus.REVOKED.isValid()).isFalse();
    }

    private static Set<PassStatus> allowedOrEmpty(Set<PassStatus> allowed) {
        return allowed.isEmpty() ? EnumSet.noneOf(PassStatus.class) : allowed;
    }
}
