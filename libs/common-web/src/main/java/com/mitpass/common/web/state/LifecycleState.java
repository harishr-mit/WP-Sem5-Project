package com.mitpass.common.web.state;

import com.mitpass.common.web.error.IllegalTransitionException;
import java.util.Set;

/**
 * A state of an explicit state machine ({@code PROJECT.md} §3, {@code docs/DECISIONS.md} D10). The status enum holds
 * the transition table; every move goes through {@link #require}, which throws {@link IllegalTransitionException}
 * (409) for anything the table doesn't allow.
 *
 * @param <S> the status enum itself
 */
public interface LifecycleState<S extends Enum<S> & LifecycleState<S>> {

    /** The states this one may move to, in the order a client should list them. Empty for a final state. */
    Set<S> allowedNext();

    default boolean canMoveTo(S target) {
        return allowedNext().contains(target);
    }

    /** Throws unless the table allows {@code from → to}. */
    static <S extends Enum<S> & LifecycleState<S>> void require(S from, S to) {
        require(from, to, from.allowedNext());
    }

    /**
     * Throws unless {@code to} is in {@code allowedForCaller}: the table's moves narrowed to what this caller may do
     * (for example, only the scheduler may make an event LIVE). The 409 lists the caller's options, not the table's.
     */
    static <S extends Enum<S> & LifecycleState<S>> void require(S from, S to, Set<S> allowedForCaller) {
        if (!from.canMoveTo(to) || !allowedForCaller.contains(to)) {
            throw new IllegalTransitionException(
                    from.name(),
                    to.name(),
                    allowedForCaller.stream()
                            .filter(from::canMoveTo)
                            .map(Enum::name)
                            .toList());
        }
    }
}
