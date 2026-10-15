package com.mitpass.portal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The portal's only door to the backend: token relay, correlation id, and ProblemDetail mapping (D9). */
class GatewayClientTest {

    MockRestServiceServer server;
    GatewayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GatewayClient(
                builder, "http://gateway.test", JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void addsTheSessionTokenAndCorrelationIdServerSide() {
        SecurityContextHolder.getContext().setAuthentication(TestTokens.login(TestTokens.user("STUDENT")));
        MDC.put(CorrelationIdFilter.MDC_KEY, "portal-abc");
        server.expect(requestTo("http://gateway.test/api/registrations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.ACCESS))
                .andExpect(header("X-Correlation-Id", "portal-abc"))
                .andExpect(header("Idempotency-Key", "key-1"))
                .andExpect(content().json("{\"eventId\":7}"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .header("Idempotent-Replayed", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"00000000-0000-4000-8000-000000000099\",\"eventId\":7,"
                                + "\"status\":\"WAITLISTED\",\"waitlistPosition\":3,\"unknownField\":1}"));

        RegisterResult result = client.register(7, "key-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.registration().status()).isEqualTo("WAITLISTED");
        assertThat(result.registration().waitlistPosition()).isEqualTo(3);
        server.verify();
    }

    @Test
    void anonymousCallsCarryNoToken() {
        server.expect(requestTo("http://gateway.test/api/categories"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"code\":\"TECHNICAL\",\"name\":\"Technical\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.categories()).hasSize(1);
    }

    @Test
    void problemDetailsBecomeApiProblems() {
        server.expect(requestTo("http://gateway.test/api/auth/signup"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"urn:mitpass:problem:validation","title":"Validation failed","status":400,
                                 "detail":"1 field is invalid","errors":[{"field":"email","message":"not allowed"}]}
                                """));

        assertThatThrownBy(() -> client.signup("A", "a@yahoo.com", "Techfest2026", "CSE", 1, null))
                .isInstanceOfSatisfying(ApiProblemException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.is("validation")).isTrue();
                    assertThat(ex.getErrors()).extracting("field").containsExactly("email");
                });
    }

    @Test
    void notEligibleCarriesTheReasons() {
        SecurityContextHolder.getContext().setAuthentication(TestTokens.login(TestTokens.user("STUDENT")));
        server.expect(requestTo("http://gateway.test/api/registrations"))
                .andRespond(
                        withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                .body(
                                        "{\"type\":\"urn:mitpass:problem:not-eligible\",\"status\":422,"
                                                + "\"detail\":\"Open to CSE only\",\"reasons\":[\"Open to CSE only (you are in AERO)\"]}"));

        assertThatThrownBy(() -> client.register(1, "k"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        ex -> assertThat(ex.getReasons()).containsExactly("Open to CSE only (you are in AERO)"));
    }

    @Test
    void aRejectedTokenMeansTheSessionExpired() {
        SecurityContextHolder.getContext().setAuthentication(TestTokens.login(TestTokens.user("STUDENT")));
        server.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.myRegistrations()).isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void aWrongPasswordIsAnApiProblemNotAnExpiredSession() {
        server.expect(requestTo("http://gateway.test/api/auth/login"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:mitpass:problem:invalid-credentials\",\"status\":401}"));

        assertThatThrownBy(() -> client.login("a@b.c", "x"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        ex -> assertThat(ex.is("invalid-credentials")).isTrue());
    }

    @Test
    void aNonJsonErrorStillBecomesAnApiProblem() {
        server.expect(requestTo("http://gateway.test/api/events/5"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("upstream down"));

        assertThatThrownBy(() -> client.event(5))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(503));
    }
}
