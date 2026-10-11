package com.mitpass.common.web.error;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error with its own status and problem {@code type}, for cases the generic exceptions don't cover (for example
 * {@code 401 invalid-credentials} or {@code 423 account-locked} with a {@code Retry-After} header).
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final Map<String, String> headers;

    public ProblemException(HttpStatus status, URI type, String title, String detail) {
        this(status, type, title, detail, Map.of());
    }

    public ProblemException(HttpStatus status, URI type, String title, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.headers = Map.copyOf(headers);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public URI getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
