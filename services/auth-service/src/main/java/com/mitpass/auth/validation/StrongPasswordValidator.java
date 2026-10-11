package com.mitpass.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    static final int MIN_BYTES = 8;
    static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true; // @NotNull reports a missing password
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        String error = null;
        if (bytes < MIN_BYTES) {
            error = "must be at least " + MIN_BYTES + " characters";
        } else if (bytes > MAX_BYTES) {
            error = "must be at most " + MAX_BYTES + " bytes (UTF-8)";
        } else if (password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            error = "must contain at least one letter and one digit";
        }
        if (error == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(error).addConstraintViolation();
        return false;
    }
}
