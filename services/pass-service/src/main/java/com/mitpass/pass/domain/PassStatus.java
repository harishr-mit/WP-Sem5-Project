package com.mitpass.pass.domain;

import com.mitpass.common.web.state.LifecycleState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pass lifecycle (PROJECT.md §3, docs/DECISIONS.md D12). ACTIVE means the live code is unlocked (2 h before doors open).
 * ISSUED → EXPIRED covers a pass whose event ended before it unlocked. EXPIRED and REVOKED are final.
 */
public enum PassStatus implements LifecycleState<PassStatus> {
    ISSUED,
    ACTIVE,
    EXPIRED,
    REVOKED;

    private static final Map<PassStatus, Set<PassStatus>> NEXT = new EnumMap<>(PassStatus.class);

    static {
        NEXT.put(ISSUED, EnumSet.of(ACTIVE, REVOKED, EXPIRED));
        NEXT.put(ACTIVE, EnumSet.of(EXPIRED, REVOKED));
        NEXT.put(EXPIRED, EnumSet.noneOf(PassStatus.class));
        NEXT.put(REVOKED, EnumSet.noneOf(PassStatus.class));
    }

    @Override
    public Set<PassStatus> allowedNext() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }

    /** Can still get someone in (ISSUED or ACTIVE). */
    public boolean isValid() {
        return this == ISSUED || this == ACTIVE;
    }
}
