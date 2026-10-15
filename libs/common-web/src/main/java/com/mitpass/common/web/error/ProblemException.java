package com.mitpass.common.web.error;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error with its own status and problem {@code type}, for cases the generic exceptions don't cover (for example
 * {@code 401 invalid-credentials} or {@code 423 account-locked} with a {@code Retry-After} header). {@code properties}
 * become extension members of the ProblemDetail (for example {@code reasons} or {@code allowedStates}).
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final Map<String, String> headers;
    private final Map<String, Object> properties;

    public ProblemException(HttpStatus status, URI type, String title, String detail) {
        this(status, type, title, detail, Map.of());
    }

    public ProblemException(HttpStatus status, URI type, String title, String detail, Map<String, String> headers) {
        this(status, type, title, detail, headers, Map.of());
    }

    public ProblemException(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            Map<String, String> headers,
            Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.headers = Map.copyOf(headers);
        this.properties = Map.copyOf(properties);
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

    public Map<String, Object> getProperties() {
        return properties;
    }
}
