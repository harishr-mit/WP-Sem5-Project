package com.mitpass.pass.service;

import com.mitpass.pass.config.PassProperties;
import com.mitpass.pass.domain.EventRef;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The times a pass's life depends on, all derived from its event (docs/DECISIONS.md D12). */
@Component
public class PassPolicy {

    private final PassProperties properties;

    public PassPolicy(PassProperties properties) {
        this.properties = properties;
    }

    /** The live code unlocks (ISSUED → ACTIVE): 2 h before doors open by default. */
    public Instant unlocksAt(EventRef event) {
        return event.getStartsAt().minus(properties.codeUnlockBeforeStart());
    }

    /** Valid passes expire (if no COMPLETED arrived first). */
    public Instant expiresAt(EventRef event) {
        return event.getEndsAt().plus(properties.expireAfterEnd());
    }

    /** A static (PDF) token is valid until then. */
    public Instant staticTokenExpiry(EventRef event) {
        return event.getEndsAt().plus(properties.staticValidAfterEnd());
    }

    public boolean isUnlocked(EventRef event, Instant now) {
        return !now.isBefore(unlocksAt(event));
    }

    public boolean isOver(EventRef event, Instant now) {
        return !now.isBefore(expiresAt(event));
    }
}
