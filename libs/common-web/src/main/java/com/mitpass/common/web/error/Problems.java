package com.mitpass.common.web.error;

import com.mitpass.common.web.correlation.CorrelationIdFilter;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds MITPass {@link ProblemDetail}s outside the MVC exception handler, e.g. in Spring Security's entry points, so
 * filter-level errors look exactly like the ones {@link GlobalExceptionHandler} produces.
 */
public final class Problems {

    private Problems() {}

    /** A problem with {@code type}, {@code title}, {@code detail}, {@code instance} and the current correlation id. */
    public static ProblemDetail create(HttpStatusCode status, URI type, String title, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
