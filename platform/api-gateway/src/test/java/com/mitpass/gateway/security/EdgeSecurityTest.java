package com.mitpass.gateway.security;

import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestReactiveSecurityConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The edge pre-check. No services run here (Eureka is off), so a request that passes the edge gets 503 from the router:
 * "not 401" is what these tests look for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
@Import(TestReactiveSecurityConfig.class)
class EdgeSecurityTest {

    @Autowired
    WebTestClient web;

    @Test
    void publicPathsPassWithoutAToken() {
        web.get().uri("/api/events").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        web.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        web.get().uri("/.well-known/jwks.json").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void protectedPathWithoutATokenIsA401ProblemAtTheEdge() {
        web.post()
                .uri("/api/events")
                .header("X-Correlation-Id", "edge-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals("X-Correlation-Id", "edge-test-1")
                .expectHeader()
                .exists(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:mitpass:problem:unauthorized")
                .jsonPath("$.instance")
                .isEqualTo("/api/events")
                .jsonPath("$.correlationId")
                .isEqualTo("edge-test-1");
    }

    @Test
    void validTokenPassesTheEdge() {
        web.get()
                .uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.STUDENT))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Phase 3: registrations are routed to event-service (503 = route found, no instance in this test; 404 = none). */
    @Test
    void registrationsAreRoutedToEventServiceAndNeedAToken() {
        web.post()
                .uri("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"eventId\":1}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        web.get()
                .uri("/api/registrations/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.STUDENT))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    static Stream<Arguments> badTokens() {
        return Stream.of(
                Arguments.of("forged payload", TestJwtIssuer.forged(TestJwtIssuer.token(Roles.STUDENT))),
                Arguments.of("expired", TestJwtIssuer.expired(Roles.ADMIN)),
                Arguments.of("untrusted key", TestJwtIssuer.signedByOtherKey(Roles.ADMIN)),
                Arguments.of("alg none", TestJwtIssuer.algNone(Roles.ADMIN)),
                Arguments.of("wrong audience", TestJwtIssuer.wrongAudience(Roles.ADMIN)));
    }

    @ParameterizedTest(name = "{0} → 401 at the edge")
    @MethodSource("badTokens")
    void badTokensStopAtTheEdge(String kind, String token) {
        web.get()
                .uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.detail")
                .value(detail -> org.assertj.core.api.Assertions.assertThat((String) detail)
                        .startsWith("The access token was rejected"));
    }

    @Test
    void corsPreflightFromThePortalIsAllowed() {
        web.method(HttpMethod.OPTIONS)
                .uri("/api/events")
                .header(HttpHeaders.ORIGIN, "http://localhost:8090")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8090");
    }

    @Test
    void corsPreflightFromAnotherOriginIsRefused() {
        web.method(HttpMethod.OPTIONS)
                .uri("/api/events")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void a401FromTheEdgeIsReadableByThePortal() {
        web.post()
                .uri("/api/events")
                .header(HttpHeaders.ORIGIN, "http://localhost:8090")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8090");
    }
}
