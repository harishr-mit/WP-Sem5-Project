package com.mitpass.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field sign-up rules: the email domain policy, the register number agreeing with a college address, and the
 * password not containing the email's name. Each failure is reported on its own field.
 */
@Documented
@Constraint(validatedBy = SignupRequestValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSignup {

    String message() default "invalid sign-up";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
