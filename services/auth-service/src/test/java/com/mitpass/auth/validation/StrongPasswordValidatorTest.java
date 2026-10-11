package com.mitpass.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StrongPasswordValidatorTest {

    record Form(@StrongPassword String password) {}

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"Passw0rd!", "techfest2026", "a1234567", "குறியீடு2026"})
    void acceptsLettersAndDigits(String password) {
        assertThat(errors(password)).isEmpty();
    }

    @Test
    void rejectsShortPasswords() {
        assertThat(errors("abc123")).containsExactly("must be at least 8 characters");
    }

    @Test
    void rejectsPasswordsWithoutADigitOrALetter() {
        assertThat(errors("onlyletters")).containsExactly("must contain at least one letter and one digit");
        assertThat(errors("1234567890")).containsExactly("must contain at least one letter and one digit");
    }

    @Test
    void limitIsSeventyTwoBytesNotCharacters() {
        // 30 Tamil letters are 90 bytes in UTF-8: too long for BCrypt even though it's only 31 characters.
        String tamil = "க".repeat(30) + "1";
        assertThat(errors(tamil)).containsExactly("must be at most 72 bytes (UTF-8)");
        assertThat(errors("a".repeat(71) + "1")).isEmpty();
        assertThat(errors("a".repeat(72) + "1")).containsExactly("must be at most 72 bytes (UTF-8)");
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(errors(null)).isEmpty();
    }

    private static Set<String> errors(String password) {
        return VALIDATOR.validate(new Form(password)).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }
}
