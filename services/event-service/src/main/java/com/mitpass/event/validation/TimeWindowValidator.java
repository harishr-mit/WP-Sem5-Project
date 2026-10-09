package com.mitpass.event.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Instant;

public class TimeWindowValidator implements ConstraintValidator<ValidTimeWindow, TimeWindow> {

    @Override
    public boolean isValid(TimeWindow window, ConstraintValidatorContext context) {
        if (window == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;

        Instant starts = window.startsAt();
        Instant opens = window.registrationOpensAt();
        Instant closes = window.registrationClosesAt();

        // Missing values are reported by @NotNull on the fields themselves.
        if (starts != null && window.endsAt() != null && !window.endsAt().isAfter(starts)) {
            valid = violation(context, "endsAt", "must be after startsAt");
        }
        if (opens != null && closes != null && !closes.isAfter(opens)) {
            valid = violation(context, "registrationClosesAt", "must be after registrationOpensAt");
        }
        if (closes != null && starts != null && closes.isAfter(starts)) {
            valid = violation(context, "registrationClosesAt", "must not be after startsAt");
        }
        if (opens != null && starts != null && !opens.isBefore(starts)) {
            valid = violation(context, "registrationOpensAt", "must be before startsAt");
        }
        return valid;
    }

    private static boolean violation(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
        return false;
    }
}
