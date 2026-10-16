package com.mitpass.event.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A pass colour: {@code #RRGGBB}, dark enough that a QR printed in it on white still scans (contrast ratio at least
 * 4.5:1 against white, WCAG). {@code null} is valid (the default colour).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PassColorValidator.class)
public @interface PassColor {

    String message() default "must be a #RRGGBB colour with a contrast ratio of at least 4.5 against white";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
