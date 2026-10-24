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

    /** Phase 4: QR images and PDFs come back as bytes with their headers; the token rides along server side only. */
    @Test
    void passMediaIsReadAsBytesWithItsHeaders() {
        SecurityContextHolder.getContext().setAuthentication(TestTokens.login(TestTokens.user("STUDENT")));
        java.util.UUID pass = java.util.UUID.fromString("00000000-0000-4000-8000-0000000000b1");
        byte[] pdf = "%PDF-1.4 fake".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.expect(requestTo("http://gateway.test/api/passes/" + pass + "/ticket.pdf"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.ACCESS))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PDF_VALUE))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"X26-000001-5.pdf\""));

        ApiModels.Binary binary = client.ticketPdf(pass);

        assertThat(binary.body()).isEqualTo(pdf);
        assertThat(binary.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(binary.contentDisposition()).contains("X26-000001-5.pdf");
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
    void theOrganizersListAsksForUpToAHundredEvents() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/events?page=0&size=100")))
                .andRespond(withSuccess(
                        "{\"content\":[],\"page\":0,\"size\":100,\"totalElements\":0,\"totalPages\":0}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.events(null, null, java.util.UUID.randomUUID(), 0).content())
                .isEmpty();
        server.verify();
    }

    @Test
    void aRejectedTokenMeansTheSessionExpired() {
        SecurityContextHolder.getContext().setAuthentication(TestTokens.login(TestTokens.user("STUDENT")));
        server.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.myRegistrations()).isInstanceOf(SessionExpiredException.class);
    }

    /** Phase 6 (plan §7): a 401 gets one refresh and one retry with the new token. */
    @Test
    void aRejectedTokenIsRefreshedOnceAndTheCallRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer retrying = MockRestServiceServer.bindTo(builder).build();
        StubTokens tokens = new StubTokens("old-token", "new-token");
        GatewayClient refreshing = new GatewayClient(
                builder, "http://gateway.test", JsonMapper.builder().build(), tokens);
        retrying.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer old-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        retrying.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new-token"))
                .andRespond(withSuccess(
                        "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}",
                        MediaType.APPLICATION_JSON));

        assertThat(refreshing.myRegistrations().content()).isEmpty();
        assertThat(tokens.rejected).containsExactly("old-token");
        retrying.verify();
    }

    @Test
    void aSecond401AfterTheRefreshEndsTheSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer retrying = MockRestServiceServer.bindTo(builder).build();
        GatewayClient refreshing = new GatewayClient(
                builder, "http://gateway.test", JsonMapper.builder().build(), new StubTokens("old", "new"));
        retrying.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        retrying.expect(requestTo("http://gateway.test/api/registrations/me?size=50"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(refreshing::myRegistrations).isInstanceOf(SessionExpiredException.class);
    }

    /** Hands out {@code first}, and {@code second} once {@code first} was rejected. */
    static final class StubTokens implements AccessTokenSource {
        final java.util.List<String> rejected = new java.util.ArrayList<>();
        private String token;
        private final String next;

        StubTokens(String first, String next) {
            this.token = first;
            this.next = next;
        }

        @Override
        public String current() {
            return token;
        }

        @Override
        public String afterRejection(String old) {
            rejected.add(old);
            token = next;
            return next;
        }
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
    void loginForwardsTheBrowsersIpForTheGatewaysRateLimit() {
        var page = new org.springframework.mock.web.MockHttpServletRequest();
        page.setRemoteAddr("192.168.137.42");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(page));
        try {
            server.expect(requestTo("http://gateway.test/api/auth/login"))
                    .andExpect(header("X-Forwarded-For", "192.168.137.42"))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body("{\"type\":\"urn:mitpass:problem:too-many-requests\",\"status\":429}"));

            assertThatThrownBy(() -> client.login("a@b.c", "x"))
                    .isInstanceOfSatisfying(
                            ApiProblemException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(429));
            server.verify();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
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

    /** Phase 5: a refused scan is an answer, not an error; a real error still raises. */
    @Test
    void scanRefusalsComeBackAsAnswersWithTheirColour() {
        server.expect(requestTo("http://gateway.test/api/scans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"passNumber\":\"X26-000001-5\",\"gateId\":11,\"mode\":\"IN\"}"))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                .body(
                                        "{\"type\":\"urn:mitpass:problem:scan/already-checked-in\",\"status\":409,"
                                                + "\"result\":\"ALREADY_CHECKED_IN\",\"message\":\"Already checked in at 09:02 at MAIN.\","
                                                + "\"color\":\"AMBER\",\"attendee\":{\"passNumber\":\"X26-000001-5\",\"holderName\":\"Priya\"}}"));
        server.expect(requestTo("http://gateway.test/api/scans"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:mitpass:problem:validation\",\"status\":400,\"detail\":\"bad\"}"));

        ApiModels.ScanAnswer answer = client.scan(null, "X26-000001-5", 11, "portal-1", "IN");

        assertThat(answer.result()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(answer.colorClass()).isEqualTo("amber");
        assertThat(answer.accepted()).isFalse();
        assertThat(answer.attendee().holderName()).isEqualTo("Priya");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.scan("t", null, 11, "portal-1", "IN"))
                .isInstanceOf(ApiProblemException.class);
        server.verify();
    }
}
