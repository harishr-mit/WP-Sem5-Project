package com.mitpass.common.web.error;

/** One entry of the {@code errors} array in a validation {@link org.springframework.http.ProblemDetail}. */
public record FieldViolation(String field, String message) {}
