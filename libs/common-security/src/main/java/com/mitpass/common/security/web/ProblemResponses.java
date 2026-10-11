package com.mitpass.common.security.web;

import com.mitpass.common.web.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a ProblemDetail straight to the servlet response. Spring Security's entry points run in the filter chain,
 * before Spring MVC, so they can't return a body through the MVC exception handlers.
 */
final class ProblemResponses {

    private static final Logger log = LoggerFactory.getLogger(ProblemResponses.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemResponses() {}

    static ProblemDetail problem(HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        return Problems.create(status, type, title, detail, request.getRequestURI());
    }

    static void write(HttpServletResponse response, ProblemDetail problem, HttpServletRequest request)
            throws IOException {
        // Same one-line format as GlobalExceptionHandler; the log pattern adds the correlation id.
        log.info("{} uri={}: {}", problem.getStatus(), request.getRequestURI(), problem.getDetail());
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), asMap(problem));
    }

    /** Flattened like Spring MVC's ProblemDetail serialization: extra properties sit at the top level. */
    static Map<String, Object> asMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", String.valueOf(problem.getType()));
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
