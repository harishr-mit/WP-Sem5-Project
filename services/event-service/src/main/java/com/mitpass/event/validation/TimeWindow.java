package com.mitpass.event.validation;

import java.time.Instant;

/** A request with a start/end and, optionally, a registration window. Checked by {@link ValidTimeWindow}. */
public interface TimeWindow {

    Instant startsAt();

    Instant endsAt();

    default Instant registrationOpensAt() {
        return null;
    }

    default Instant registrationClosesAt() {
        return null;
    }
}
