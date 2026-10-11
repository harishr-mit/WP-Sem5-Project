package com.mitpass.common.security.web;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Turns {@code mitpass.security.public-paths} entries ({@code "GET /api/events/**"} or {@code "/x/**"}) into matchers. */
public final class PublicPaths {

    /** Always public: health/info probes, the OpenAPI document, Swagger UI and the error page. */
    public static final List<String> INFRASTRUCTURE = List.of(
            "GET /actuator/health",
            "GET /actuator/health/**",
            "GET /actuator/info",
            "GET /v3/api-docs",
            "GET /v3/api-docs/**",
            "GET /swagger-ui.html",
            "GET /swagger-ui/**",
            "/error");

    private PublicPaths() {}

    public static RequestMatcher[] matchers(List<String> configured) {
        return Stream.concat(INFRASTRUCTURE.stream(), configured.stream())
                .map(PublicPaths::matcher)
                .toArray(RequestMatcher[]::new);
    }

    static RequestMatcher matcher(String spec) {
        String[] parts = spec.trim().split("\\s+", 2);
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        return parts.length == 2
                ? builder.matcher(HttpMethod.valueOf(parts[0].toUpperCase()), parts[1])
                : builder.matcher(parts[0]);
    }
}
