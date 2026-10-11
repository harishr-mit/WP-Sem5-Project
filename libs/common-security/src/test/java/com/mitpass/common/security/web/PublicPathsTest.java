package com.mitpass.common.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class PublicPathsTest {

    @Test
    void methodAndPatternMustBothMatch() {
        RequestMatcher matcher = PublicPaths.matcher("GET /api/events/**");

        assertThat(matcher.matches(request("GET", "/api/events/3/sessions"))).isTrue();
        assertThat(matcher.matches(request("POST", "/api/events"))).isFalse();
        assertThat(matcher.matches(request("GET", "/api/venues"))).isFalse();
    }

    @Test
    void patternWithoutMethodMatchesAnyMethod() {
        RequestMatcher matcher = PublicPaths.matcher("/api/auth/**");

        assertThat(matcher.matches(request("POST", "/api/auth/login"))).isTrue();
        assertThat(matcher.matches(request("GET", "/api/auth/verify-email"))).isTrue();
    }

    @Test
    void infrastructurePathsAreAlwaysIncluded() {
        RequestMatcher[] matchers = PublicPaths.matchers(java.util.List.of());

        assertThat(matchers)
                .anySatisfy(m -> assertThat(m.matches(request("GET", "/actuator/health")))
                        .isTrue());
        assertThat(matchers)
                .noneSatisfy(m ->
                        assertThat(m.matches(request("GET", "/actuator/env"))).isTrue());
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        return request;
    }
}
