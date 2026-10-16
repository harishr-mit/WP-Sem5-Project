package com.mitpass.common.pass;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable pass numbers: {@code <EVENTCODE>-<NNNNNN>-<C>}, for example {@code TECHFEST26-000042-7}
 * (docs/DECISIONS.md D12). {@code C} is a Luhn mod 10 check digit over the event code and the sequence, with letters
 * expanded to two digits as in an ISIN (A = 10 … Z = 35). Every single mistyped digit is caught; a mistyped letter is
 * caught about 9 times in 10 (no single decimal digit can catch them all), and P5 matches the code to the event anyway.
 */
public final class PassNumber {

    private static final Pattern FORMAT = Pattern.compile("^([A-Z0-9]{3,16})-(\\d{6,9})-(\\d)$");

    private PassNumber() {}

    /** The number for the {@code sequence}-th pass of an event. */
    public static String of(String eventCode, int sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Pass sequence starts at 1");
        }
        String code = eventCode.toUpperCase(Locale.ROOT);
        String seq = "%06d".formatted(sequence);
        return code + "-" + seq + "-" + checkDigit(code + seq);
    }

    /** Format and check digit are right (for manual entry at the gate, P5). Case and spaces around are forgiven. */
    public static boolean isValid(String number) {
        if (number == null) {
            return false;
        }
        Matcher matcher = FORMAT.matcher(number.strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return false;
        }
        int expected = checkDigit(matcher.group(1) + matcher.group(2));
        return expected == matcher.group(3).charAt(0) - '0';
    }

    /** The stored form (trimmed, upper case) of a number typed at the gate, if its format and check digit are right. */
    public static Optional<String> normalize(String number) {
        return isValid(number) ? Optional.of(number.strip().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** The event-code part of a valid number ({@code TECHFEST26} for {@code TECHFEST26-000042-7}). */
    public static String eventCode(String number) {
        Matcher matcher = FORMAT.matcher(number.strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a pass number: " + number);
        }
        return matcher.group(1);
    }

    /** Luhn check digit for letters and digits, letters expanded ISIN style. */
    static int checkDigit(String alphanumeric) {
        StringBuilder digits = new StringBuilder();
        for (char c : alphanumeric.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                throw new IllegalArgumentException("Only A-Z and 0-9 are allowed: " + alphanumeric);
            }
        }
        // Double every second digit from the right, starting with the rightmost (the check digit goes after it).
        int sum = 0;
        boolean doubleIt = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
