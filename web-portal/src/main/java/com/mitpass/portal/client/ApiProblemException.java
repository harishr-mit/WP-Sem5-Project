package com.mitpass.portal.client;

import com.mitpass.portal.client.ApiModels.FieldError;
import java.util.List;

/**
 * A ProblemDetail (D6) returned by a backend service, for pages to show: {@code detail} as a flash message, field
 * {@code errors} on the form, {@code reasons} for "not eligible".
 */
public class ApiProblemException extends RuntimeException {

    private final int status;
    private final String type;
    private final List<FieldError> errors;
    private final List<String> reasons;

    public ApiProblemException(int status, String type, String detail, List<FieldError> errors, List<String> reasons) {
        super(detail != null ? detail : "Request failed with status " + status);
        this.status = status;
        this.type = type;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public int getStatus() {
        return status;
    }

    /** The full {@code urn:mitpass:problem:…} type, or null. */
    public String getType() {
        return type;
    }

    /** True if the problem type ends with {@code :slug}, e.g. {@code is("email-not-verified")}. */
    public boolean is(String slug) {
        return type != null && type.endsWith(":" + slug);
    }

    public List<FieldError> getErrors() {
        return errors;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
