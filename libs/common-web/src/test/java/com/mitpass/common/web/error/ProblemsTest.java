package com.mitpass.common.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.web.correlation.CorrelationIdFilter;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsAProblemWithTheCurrentCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "abc-123");

        ProblemDetail problem = Problems.create(
                HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized", "Token expired", "/api/x");

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:mitpass:problem:unauthorized"));
        assertThat(problem.getTitle()).isEqualTo("Unauthorized");
        assertThat(problem.getDetail()).isEqualTo("Token expired");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/x"));
        assertThat(problem.getProperties()).containsEntry("correlationId", "abc-123");
    }

    @Test
    void worksWithoutCorrelationIdOrInstance() {
        ProblemDetail problem = Problems.create(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden", "No", null);

        assertThat(problem.getInstance()).isNull();
        assertThat(problem.getProperties()).isNull();
    }
}
