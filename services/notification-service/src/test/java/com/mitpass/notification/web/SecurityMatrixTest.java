package com.mitpass.notification.web;

import static com.mitpass.security.test.SecurityMatrix.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.messaging.DeadLetters;
import com.mitpass.common.security.Roles;
import com.mitpass.notification.FakeEventDirectory;
import com.mitpass.notification.Fixtures;
import com.mitpass.notification.NotificationIntegrationTest;
import com.mitpass.notification.TestClock;
import com.mitpass.security.test.SecurityMatrix;
import com.mitpass.security.test.SecurityMatrix.Rule;
import com.mitpass.security.test.TestJwtIssuer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Every notification-service endpoint × every kind of caller (D7). Writes
 * {@code target/security-matrix/notification-service.md} for {@code docs/security-matrix.md}.
 */
@NotificationIntegrationTest
class SecurityMatrixTest {

    static final String STUDENT = "student";
    static final String ORGANIZER = "organizer";
    static final String ADMIN = "admin";
    static final String SERVICE = "service";

    static final Map<String, String> TOKENS = new LinkedHashMap<>();

    static {
        TOKENS.put(STUDENT, TestJwtIssuer.token(Roles.STUDENT));
        TOKENS.put(ORGANIZER, TestJwtIssuer.token(Roles.ORGANIZER));
        TOKENS.put(ADMIN, TestJwtIssuer.token(Roles.ADMIN));
        TOKENS.put(SERVICE, TestJwtIssuer.token(Roles.SERVICE));
    }

    record Endpoint(HttpMethod method, String template, String path, Rule rule) {}

    /** No broker in this test: the dead-letter rows check who may call, not RabbitMQ. */
    @MockitoBean
    DeadLetters deadLetters;

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeEventDirectory events;

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        var event = Fixtures.event(TestClock.START.plus(Duration.ofDays(3)));
        events.put(event);

        List<Endpoint> endpoints = List.of(
                new Endpoint(
                        HttpMethod.GET,
                        "/api/notifications/events/{id}/calendar.ics",
                        "/api/notifications/events/" + event.id() + "/calendar.ics",
                        Rule.publicEndpoint()),
                new Endpoint(HttpMethod.GET, "/api/dead-letters", "/api/dead-letters", Rule.only(ADMIN)),
                new Endpoint(
                        HttpMethod.POST,
                        "/api/dead-letters/{messageId}/replay",
                        "/api/dead-letters/no-such-message/replay",
                        Rule.only(ADMIN)));

        List<String> callers = List.of(ANONYMOUS, STUDENT, ORGANIZER, ADMIN, SERVICE);
        SecurityMatrix matrix = new SecurityMatrix("notification-service", callers);
        for (Endpoint endpoint : endpoints) {
            for (String caller : callers) {
                MockHttpServletRequestBuilder request =
                        MockMvcRequestBuilders.request(endpoint.method(), endpoint.path());
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
        matrix.writeTo(Path.of("target", "security-matrix", "notification-service.md"));
        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(endpoints.size() * callers.size());
    }
}
