package com.mitpass.auth.validation;

import com.mitpass.auth.service.EmailPolicy;
import com.mitpass.auth.web.dto.SignupRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;

/** Spring creates this validator, so it can use the {@link EmailPolicy} bean. */
public class SignupRequestValidator implements ConstraintValidator<ValidSignup, SignupRequest> {

    /** Shorter email names ("ab@…") would reject too many reasonable passwords. */
    private static final int MIN_NAME_LENGTH_TO_CHECK = 4;

    private final EmailPolicy policy;

    public SignupRequestValidator(EmailPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
        if (request == null || request.email() == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;

        EmailPolicy.Evaluation email = policy.evaluate(request.email());
        if (!email.allowed()) {
            valid = violation(context, "email", email.error());
        } else if (email.registerNumber() != null
                && request.registerNumber() != null
                && !request.registerNumber().isBlank()
                && !email.registerNumber().equals(request.registerNumber().trim())) {
            valid = violation(
                    context,
                    "registerNumber",
                    "must match the register number in your email (" + email.registerNumber() + ")");
        }

        String name = email.email().contains("@")
                ? email.email().substring(0, email.email().indexOf('@'))
                : "";
        if (request.password() != null
                && name.length() >= MIN_NAME_LENGTH_TO_CHECK
                && request.password().toLowerCase(Locale.ROOT).contains(name)) {
            valid = violation(context, "password", "must not contain your email address");
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
