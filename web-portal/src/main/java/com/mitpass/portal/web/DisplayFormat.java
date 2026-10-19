package com.mitpass.portal.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Display helpers for templates, as {@code ${@fmt.when(event.startsAt())}}: dates in campus time and in the page's
 * language, state names from the message bundle ({@code state.CONFIRMED}), the colour of a state chip, initials.
 */
@Component("fmt")
public class DisplayFormat {

    static final ZoneId CAMPUS = ZoneId.of("Asia/Kolkata");
    static final int MRZ_WIDTH = 30;

    private static final Set<String> GOOD = Set.of("CONFIRMED", "ACTIVE", "REGISTRATION_OPEN", "IN", "ACCEPTED", "UP");
    private static final Set<String> WAITING =
            Set.of("WAITLISTED", "PENDING", "ISSUED", "PUBLISHED", "REGISTRATION_CLOSED", "OUT", "DRAFT", "UNKNOWN");
    private static final Set<String> BAD = Set.of("CANCELLED", "REVOKED", "EXPIRED", "DOWN", "OUT_OF_SERVICE");

    private final MessageSource messages;

    public DisplayFormat(MessageSource messages) {
        this.messages = messages;
    }

    /** "Sat 14 Nov 2026, 09:00" (in Tamil: Tamil day and month names). */
    public String when(Instant instant) {
        return format(instant, "EEE d MMM yyyy, HH:mm");
    }

    /** "Sat 14 Nov, 09:00". */
    public String shortWhen(Instant instant) {
        return format(instant, "EEE d MMM, HH:mm");
    }

    /** "09:00". */
    public String time(Instant instant) {
        return format(instant, "HH:mm");
    }

    /** "09:00:03" (the "You're in" timeline). */
    public String seconds(Instant instant) {
        return format(instant, "HH:mm:ss");
    }

    /** Day of month for the date chip: "14". */
    public String day(Instant instant) {
        return format(instant, "d");
    }

    /** Month for the date chip: "NOV". */
    public String month(Instant instant) {
        return format(instant, "MMM").toUpperCase(locale());
    }

    /** ISO-8601, for {@code <time datetime>} and for scripts. */
    public String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private String format(Instant instant, String pattern) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern(pattern, locale()).withZone(CAMPUS).format(instant);
    }

    /** A state's label from the bundle ({@code state.REGISTRATION_OPEN} → "Registration open"); unknown → as is. */
    public String state(String state) {
        if (state == null) {
            return "";
        }
        return messages.getMessage("state." + state, null, state, locale());
    }

    /** "Confirmed", or "Waitlisted #3". */
    public String status(String status, Integer waitlistPosition) {
        String label = state(status);
        return waitlistPosition == null ? label : label + " #" + waitlistPosition;
    }

    /** CSS modifier for a state chip: green for good, amber for waiting, red for final/bad, teal for LIVE. */
    public String badgeClass(String state) {
        if (state == null) {
            return "";
        }
        if ("LIVE".equals(state)) {
            return "badge--info badge--live";
        }
        if (GOOD.contains(state)) {
            return "badge--ok";
        }
        if (WAITING.contains(state)) {
            return "badge--warn";
        }
        if (BAD.contains(state)) {
            return "badge--err";
        }
        return "";
    }

    /** Who may register, in the page's language: "CSE, IT · years 2–4", or "Open to everyone". */
    public String eligibility(com.mitpass.portal.client.ApiModels.EventDetail event) {
        java.util.List<String> departments =
                event.eligibleDepartments() == null ? java.util.List.of() : event.eligibleDepartments();
        Integer min = event.minYear();
        Integer max = event.maxYear();
        String years = null;
        if (min != null && max != null) {
            years = min.equals(max) ? msg("event.years.one", min) : msg("event.years.range", min, max);
        } else if (min != null) {
            years = msg("event.years.min", min);
        } else if (max != null) {
            years = msg("event.years.max", max);
        }
        if (departments.isEmpty() && years == null) {
            return msg("event.openToEveryone");
        }
        String depts = String.join(", ", departments);
        return departments.isEmpty() ? years : years == null ? depts : depts + " · " + years;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, locale());
    }

    /**
     * The two machine-readable lines at the foot of the pass card (the mockup's passport look; decoration only):
     * {@code MP<TECHFEST26<<RAMAN<PRIYA<<<<<<<<} and {@code TECHFEST260000427<<261114<<<<<<<<}, {@value #MRZ_WIDTH} characters each (fits a 360 px phone).
     */
    public java.util.List<String> mrz(com.mitpass.portal.client.ApiModels.Pass pass) {
        String name = pass.holderName() == null ? "" : pass.holderName().strip();
        String[] parts = name.isEmpty() ? new String[0] : name.split("\\s+");
        String holder = parts.length == 0
                ? ""
                : parts.length == 1 ? mrzText(parts[0]) : mrzText(parts[parts.length - 1]) + "<" + mrzText(parts[0]);
        String code = pass.eventCode() == null ? "" : mrzText(pass.eventCode());
        String number = pass.passNumber() == null ? "" : pass.passNumber().replace("-", "");
        String day = pass.startsAt() == null
                ? ""
                : DateTimeFormatter.ofPattern("yyMMdd").withZone(CAMPUS).format(pass.startsAt());
        return java.util.List.of(pad("MP<" + code + "<<" + holder), pad(number + "<<" + day));
    }

    private static String mrzText(String s) {
        String ascii =
                java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "<");
    }

    private static String pad(String line) {
        String cut = line.length() > MRZ_WIDTH ? line.substring(0, MRZ_WIDTH) : line;
        return cut + "<".repeat(MRZ_WIDTH - cut.length());
    }

    /** "Priya Raman" → "PR"; one name → its first two letters. */
    public String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.strip().split("[\\s@._-]+");
        String first = parts[0];
        if (parts.length == 1) {
            return first.substring(0, Math.min(2, first.length())).toUpperCase(locale());
        }
        return (first.substring(0, 1) + parts[parts.length - 1].charAt(0)).toUpperCase(locale());
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }
}
