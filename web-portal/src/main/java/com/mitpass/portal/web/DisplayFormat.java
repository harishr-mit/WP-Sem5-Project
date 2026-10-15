package com.mitpass.portal.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Dates for templates, in campus time: {@code ${@fmt.when(event.startsAt)}}. */
@Component("fmt")
public class DisplayFormat {

    private static final ZoneId CAMPUS = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(CAMPUS);

    public String when(Instant instant) {
        return instant == null ? "" : WHEN.format(instant);
    }

    /** "CONFIRMED", or "WAITLISTED #3". */
    public String status(String status, Integer waitlistPosition) {
        return waitlistPosition == null ? status : status + " #" + waitlistPosition;
    }
}
