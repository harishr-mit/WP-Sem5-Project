package com.mitpass.event.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level (cross-field) constraint on a {@link TimeWindow}: {@code endsAt} after {@code startsAt}, and the
 * registration window, when given, opens before it closes and closes no later than the start. Violations are reported
 * on the offending field, so they show up in the ProblemDetail's {@code errors[]} like any field error.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TimeWindowValidator.class)
public @interface ValidTimeWindow {

    String message() default "invalid time window";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
