package com.mitpass.checkin.domain;

import com.mitpass.common.web.state.LifecycleState;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where an attendee is (PROJECT.md §3 Presence): NOT_ARRIVED → IN ⇄ OUT. Whether OUT → IN is allowed in the same
 * session is the event's re-entry policy, checked by the check-in transaction, not an edge of this table.
 */
public enum PresenceState implements LifecycleState<PresenceState> {
    NOT_ARRIVED,
    IN,
    OUT;

    @Override
    public Set<PresenceState> allowedNext() {
        return switch (this) {
            case NOT_ARRIVED -> EnumSet.of(IN);
            case IN -> EnumSet.of(OUT);
            case OUT -> EnumSet.of(IN);
        };
    }
}
