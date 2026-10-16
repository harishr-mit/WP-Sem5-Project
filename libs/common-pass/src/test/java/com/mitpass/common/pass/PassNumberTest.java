package com.mitpass.common.pass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pass number format and check digit (D12): Luhn over the number with letters expanded as in an ISIN. */
class PassNumberTest {

    @Test
    void checkDigitMatchesPublishedIsinVectors() {
        // ISIN = 2 letters + 9 alphanumerics + Luhn check digit with the same letter expansion.
        assertThat(PassNumber.checkDigit("US037833100")).isEqualTo(5); // Apple, US0378331005
        assertThat(PassNumber.checkDigit("AU0000XVGZA")).isEqualTo(3); // AU0000XVGZA3
        assertThat(PassNumber.checkDigit("GB000263494")).isEqualTo(6); // GB0002634946
    }

    @Test
    void formatsEventCodeSequenceAndCheckDigit() {
        String number = PassNumber.of("TECHFEST26", 42);

        assertThat(number).matches("TECHFEST26-000042-\\d");
        assertThat(PassNumber.isValid(number)).isTrue();
        assertThat(PassNumber.isValid(" " + number.toLowerCase() + " ")).isTrue();
    }

    @Test
    void everyDigitTypoIsCaught() {
        String number = PassNumber.of("TECHFEST26", 42);
        int checked = 0;
        for (int i = 0; i < number.length(); i++) {
            char original = number.charAt(i);
            if (!Character.isDigit(original)) {
                continue;
            }
            for (char replacement = '0'; replacement <= '9'; replacement++) {
                if (replacement == original) {
                    continue;
                }
                String typo = number.substring(0, i) + replacement + number.substring(i + 1);
                assertThat(PassNumber.isValid(typo)).as(typo).isFalse();
                checked++;
            }
        }
        assertThat(checked).isEqualTo(9 * 9); // 2 code digits + 6 sequence digits + the check digit
    }

    /**
     * A letter expands to two digits, so Luhn can't promise to catch every letter typo. No single decimal check digit
     * can: 36 symbols on 10 values must collide (found by this test; D12 corrected). In practice about 9 in 10 are
     * caught, and at the gate (P5) the code part is also matched against the volunteer's event.
     */
    @Test
    void mostLetterTyposAreCaught() {
        String number = PassNumber.of("TECHFEST26", 42);
        int caught = 0;
        int checked = 0;
        for (int i = 0; i < number.length(); i++) {
            char original = number.charAt(i);
            if (!Character.isLetter(original)) {
                continue;
            }
            for (char replacement = 'A'; replacement <= 'Z'; replacement++) {
                if (replacement == original) {
                    continue;
                }
                checked++;
                if (!PassNumber.isValid(number.substring(0, i) + replacement + number.substring(i + 1))) {
                    caught++;
                }
            }
        }
        assertThat(checked).isEqualTo(8 * 25);
        assertThat((double) caught / checked).isGreaterThan(0.85);
    }

    @Test
    void mostAdjacentSwapsInTheSequenceAreCaught() {
        String number = PassNumber.of("RANG26", 123_456);
        String digits = number.substring(7, 13);
        int caught = 0;
        int swaps = 0;
        for (int i = 0; i < digits.length() - 1; i++) {
            if (digits.charAt(i) == digits.charAt(i + 1)) {
                continue;
            }
            String swapped = digits.substring(0, i) + digits.charAt(i + 1) + digits.charAt(i) + digits.substring(i + 2);
            swaps++;
            if (!PassNumber.isValid("RANG26-" + swapped + number.substring(13))) {
                caught++;
            }
        }
        assertThat(caught).isEqualTo(swaps); // Luhn misses only 09 <-> 90, which this number doesn't contain
    }

    @Test
    void malformedNumbersAreInvalid() {
        assertThat(PassNumber.isValid(null)).isFalse();
        assertThat(PassNumber.isValid("TECHFEST26-42-7")).isFalse();
        assertThat(PassNumber.isValid("TECHFEST26000042-7")).isFalse();
        assertThat(PassNumber.isValid("TF-000042-7")).isFalse();
    }

    @Test
    void typedNumbersAreNormalizedAndTheirEventCodeRead() {
        String number = PassNumber.of("TECHFEST26", 42);

        assertThat(PassNumber.normalize("  " + number.toLowerCase() + " ")).contains(number);
        assertThat(PassNumber.normalize("TECHFEST26-000042-0")).isEmpty();
        assertThat(PassNumber.eventCode(" " + number.toLowerCase())).isEqualTo("TECHFEST26");
        assertThatThrownBy(() -> PassNumber.eventCode("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sequenceStartsAtOne() {
        assertThatThrownBy(() -> PassNumber.of("X26", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PassNumber.of("X26", 1_000_000)).startsWith("X26-1000000-");
    }
}
