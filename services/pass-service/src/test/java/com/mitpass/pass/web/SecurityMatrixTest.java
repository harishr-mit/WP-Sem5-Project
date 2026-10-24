package com.mitpass.pass.web;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static com.mitpass.security.test.SecurityMatrix.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.Roles;
import com.mitpass.pass.FakeEventCatalog;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.messaging.PassInboxListener;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.security.test.SecurityMatrix;
import com.mitpass.security.test.SecurityMatrix.Rule;
import com.mitpass.security.test.TestJwtIssuer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Every pass-service endpoint × every kind of caller (D7), with real tokens through the real filter chain and method
 * security. Writes {@code target/security-matrix/pass-service.md} for {@code docs/security-matrix.md}.
 */
@PassIntegrationTest
class SecurityMatrixTest {

    /** No broker in this test: the dead-letter rows check who may call, not RabbitMQ (DeadLettersTest does that). */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.mitpass.common.messaging.DeadLetters deadLetters;

    static final String HOLDER = "student (holder)";
    static final String STUDENT = "student (other)";
    static final String VOLUNTEER = "volunteer";
    static final String OWNER = "organizer (owner)";
    static final String OTHER = "organizer (other)";
    static final String ADMIN = "admin";
    /** A service token (P5, D7): checkin-service's reconciler reads the copy it keeps. */
    static final String SERVICE = "service (checkin)";

    static final UUID HOLDER_ID = UUID.randomUUID();
    static final Map<String, String> TOKENS = new LinkedHashMap<>();

    static {
        TOKENS.put(HOLDER, TestJwtIssuer.tokenFor(HOLDER_ID, Roles.STUDENT));
        TOKENS.put(STUDENT, TestJwtIssuer.token(Roles.STUDENT));
        TOKENS.put(VOLUNTEER, TestJwtIssuer.token(Roles.STUDENT, Roles.VOLUNTEER));
        TOKENS.put(OWNER, TestJwtIssuer.tokenFor(PassFixtures.ORGANIZER, Roles.ORGANIZER));
        TOKENS.put(OTHER, TestJwtIssuer.token(Roles.ORGANIZER));
        TOKENS.put(ADMIN, TestJwtIssuer.token(Roles.ADMIN));
        TOKENS.put(SERVICE, TestJwtIssuer.token(Roles.SERVICE));
    }

    /** A fresh ACTIVE pass of {@link #HOLDER} for an event owned by {@link #OWNER}. */
    record Fixture(UUID pass, long event) {}

    record Endpoint(HttpMethod method, String template, Function<Fixture, String> path, Rule rule) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    PassInboxListener listener;

    @Autowired
    PassRepository passes;

    @Autowired
    FakeEventCatalog catalog;

    @Autowired
    TestClock clock;

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        clock.set(TestClock.START);
        Rule holderOwnerAdmin = Rule.only(HOLDER, OWNER, ADMIN);
        Rule ownerAdmin = Rule.only(OWNER, ADMIN);
        Rule holderOnly = Rule.only(HOLDER);
        Rule ownerAdminService = Rule.only(OWNER, ADMIN, SERVICE);

        List<Endpoint> endpoints = List.of(
                endpoint(HttpMethod.GET, "/api/passes/me", f -> "/api/passes/me", Rule.authenticated()),
                endpoint(
                        HttpMethod.GET,
                        "/api/passes?eventId=",
                        f -> "/api/passes?eventId=" + f.event(),
                        ownerAdminService),
                endpoint(
                        HttpMethod.GET,
                        "/api/passes/{id}",
                        f -> "/api/passes/" + f.pass(),
                        Rule.only(HOLDER, OWNER, ADMIN, SERVICE)),
                endpoint(
                        HttpMethod.GET,
                        "/api/passes/{id}/history",
                        f -> "/api/passes/" + f.pass() + "/history",
                        holderOwnerAdmin),
                endpoint(
                        HttpMethod.GET,
                        "/api/passes/{id}/qr/live",
                        f -> "/api/passes/" + f.pass() + "/qr/live",
                        holderOnly),
                endpoint(
                        HttpMethod.GET,
                        "/api/passes/{id}/qr/static",
                        f -> "/api/passes/" + f.pass() + "/qr/static",
                        Rule.only(HOLDER, SERVICE)), // SERVICE: the pass email (P7)
                endpoint(
                        HttpMethod.GET,
                        "/api/passes/{id}/ticket.pdf",
                        f -> "/api/passes/" + f.pass() + "/ticket.pdf",
                        holderOnly),
                endpoint(
                        HttpMethod.POST,
                        "/api/passes/{id}/revoke",
                        f -> "/api/passes/" + f.pass() + "/revoke",
                        ownerAdmin),
                // RL10: admins only (the fixture pass isn't revoked, so an admin gets 409, past security)
                endpoint(
                        HttpMethod.POST,
                        "/api/passes/{id}/reissue",
                        f -> "/api/passes/" + f.pass() + "/reissue",
                        Rule.only(ADMIN)),
                endpoint(
                        HttpMethod.GET,
                        "/api/revocations",
                        f -> "/api/revocations?since=0",
                        Rule.only(VOLUNTEER, OWNER, OTHER, ADMIN, SERVICE)),
                endpoint(
                        HttpMethod.GET,
                        "/.well-known/pass-jwks.json",
                        f -> "/.well-known/pass-jwks.json",
                        Rule.publicEndpoint()),
                endpoint(HttpMethod.POST, "/api/passes/keys/rotate", f -> "/api/passes/keys/rotate", Rule.only(ADMIN)),
                // common-messaging's dead-letter tooling (D13, P7)
                endpoint(HttpMethod.GET, "/api/dead-letters", f -> "/api/dead-letters", Rule.only(ADMIN)),
                endpoint(
                        HttpMethod.DELETE,
                        "/api/dead-letters/{messageId}",
                        f -> "/api/dead-letters/no-such-message",
                        Rule.only(ADMIN)));

        List<String> callers = List.of(ANONYMOUS, HOLDER, STUDENT, VOLUNTEER, OWNER, OTHER, ADMIN, SERVICE);
        SecurityMatrix matrix = new SecurityMatrix("pass-service", callers);
        for (Endpoint endpoint : endpoints) {
            for (String caller : callers) {
                Fixture fixture = newFixture();
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                                endpoint.method(), endpoint.path().apply(fixture))
                        .contentType(MediaType.APPLICATION_JSON);
                if (!ANONYMOUS.equals(caller)) {
                    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.get(caller));
                }
                MockHttpServletResponse response =
                        mvc.perform(request).andReturn().getResponse();
                matrix.record(
                        endpoint.method() + " " + endpoint.template(),
                        endpoint.rule(),
                        caller,
                        response.getStatus(),
                        response.getContentAsString());
            }
        }

        System.out.println(matrix.toMarkdown());
        matrix.writeTo(Path.of("target", "security-matrix", "pass-service.md"));
        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(endpoints.size() * callers.size());
    }

    private Fixture newFixture() {
        TestEvent event = PassFixtures.event(Duration.ofHours(1)); // unlocked, so the live code is served
        catalog.put(event.info(true, null));
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, HOLDER_ID)));
        return new Fixture(
                passes.findByRegistrationId(registration).orElseThrow().getId(), event.id());
    }

    private static Endpoint endpoint(HttpMethod method, String template, Function<Fixture, String> path, Rule rule) {
        return new Endpoint(method, template, path, rule);
    }
}
