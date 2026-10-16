package com.mitpass.event.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/** See {@link PassColor}. Relative luminance and contrast ratio as defined by WCAG 2. */
public class PassColorValidator implements ConstraintValidator<PassColor, String> {

    static final double MIN_CONTRAST = 4.5;
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || (HEX.matcher(value).matches() && contrastAgainstWhite(value) >= MIN_CONTRAST);
    }

    /** (1.05) / (L + 0.05), white has luminance 1. */
    public static double contrastAgainstWhite(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double luminance = 0.2126 * channel((rgb >> 16) & 0xFF)
                + 0.7152 * channel((rgb >> 8) & 0xFF)
                + 0.0722 * channel(rgb & 0xFF);
        return 1.05 / (luminance + 0.05);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
