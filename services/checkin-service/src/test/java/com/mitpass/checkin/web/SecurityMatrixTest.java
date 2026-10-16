package com.mitpass.checkin.web;

import static com.mitpass.security.test.SecurityMatrix.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.SecurityMatrix;
import com.mitpass.security.test.SecurityMatrix.Rule;
import com.mitpass.security.test.TestJwtIssuer;
import java.nio.file.Path;
import java.util.List;
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
 * Every checkin-service endpoint × every kind of caller (D7). Writes {@code target/security-matrix/checkin-service.md}.
 * An unassigned volunteer passes security on the scan endpoints and is then refused by the pipeline's first step
 * ({@code scan/unauthorized-scanner}): the matrix checks security, so that counts as "allowed".
 */
@CheckinIntegrationTest
class SecurityMatrixTest {

    static final String STUDENT = "student";
    static final String VOLUNTEER = "volunteer (assigned)";
    static final String STRANGER = "volunteer (not assigned)";
    static final String OWNER = "organizer (owner)";
    static final String OTHER = "organizer (other)";
    static final String ADMIN = "admin";
    static final String SERVICE = "service token";

    /** A fresh LIVE event with gates, an assigned volunteer and one pass. */
    record Fixture(Gate gate, PassRecord pass, String token) {}

    record Endpoint(
            HttpMethod method,
            String template,
            Function<Fixture, String> path,
            Function<Fixture, String> body,
            Rule rule) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    ScanScenario scenario;

    @Autowired
    TestClock clock;

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        clock.set(TestClock.START);
        Rule volunteers = Rule.only(VOLUNTEER, STRANGER);
        Rule ownerAdmin = Rule.only(OWNER, ADMIN);
        Function<Fixture, String> none = f -> null;

        List<Endpoint> endpoints = List.of(
                new Endpoint(
                        HttpMethod.POST,
                        "/api/scans",
                        f -> "/api/scans",
                        f -> "{\"token\":\"%s\",\"gateId\":%d,\"deviceId\":\"m-1\"}"
                                .formatted(f.token(), f.gate().main().id()),
                        volunteers),
                new Endpoint(
                        HttpMethod.GET,
                        "/api/scans/lookup",
                        f -> "/api/scans/lookup?passNumber=" + f.pass().passNumber() + "&gateId="
                                + f.gate().main().id(),
                        none,
                        volunteers),
                new Endpoint(
                        HttpMethod.GET,
                        "/api/attendance/events/{id}/summary",
                        f -> "/api/attendance/events/" + f.gate().event().id() + "/summary",
                        none,
                        ownerAdmin),
                new Endpoint(
                        HttpMethod.GET,
                        "/api/attendance/events/{id}/attendees",
                        f -> "/api/attendance/events/" + f.gate().event().id() + "/attendees",
                        none,
                        ownerAdmin),
                new Endpoint(
                        HttpMethod.GET,
                        "/api/attendance/events/{id}/export.csv",
                        f -> "/api/attendance/events/" + f.gate().event().id() + "/export.csv",
                        none,
                        ownerAdmin),
                new Endpoint(
                        HttpMethod.GET,
                        "/api/attendance/events/{id}/certificates",
                        f -> "/api/attendance/events/" + f.gate().event().id() + "/certificates",
                        none,
                        ownerAdmin),
                new Endpoint(
                        HttpMethod.POST,
                        "/api/attendance/events/{id}/sync",
                        f -> "/api/attendance/events/" + f.gate().event().id() + "/sync",
                        none,
                        ownerAdmin),
                new Endpoint(
                        HttpMethod.GET, "/api/attendance/me", f -> "/api/attendance/me", none, Rule.authenticated()));

        List<String> callers = List.of(ANONYMOUS, STUDENT, VOLUNTEER, STRANGER, OWNER, OTHER, ADMIN, SERVICE);
        SecurityMatrix matrix = new SecurityMatrix("checkin-service", callers);
        for (Endpoint endpoint : endpoints) {
            for (String caller : callers) {
                Fixture fixture = newFixture();
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                                endpoint.method(), endpoint.path().apply(fixture))
                        .contentType(MediaType.APPLICATION_JSON);
                String body = endpoint.body().apply(fixture);
                if (body != null) {
                    request.content(body);
                }
                String token = token(caller, fixture);
                if (token != null) {
                    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
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
        matrix.writeTo(Path.of("target", "security-matrix", "checkin-service.md"));
        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(endpoints.size() * callers.size());
    }

    private Fixture newFixture() {
        Gate gate = scenario.liveEvent();
        PassRecord pass = scenario.pass(gate, 1, "Matrix Holder");
        return new Fixture(gate, pass, scenario.liveToken(pass));
    }

    private static String token(String caller, Fixture fixture) {
        return switch (caller) {
            case ANONYMOUS -> null;
            case STUDENT -> TestJwtIssuer.token(Roles.STUDENT);
            case VOLUNTEER -> fixture.gate().volunteerToken();
            case STRANGER -> TestJwtIssuer.token(Roles.STUDENT, Roles.VOLUNTEER);
            case OWNER -> TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER);
            case OTHER -> TestJwtIssuer.token(Roles.ORGANIZER);
            case ADMIN -> TestJwtIssuer.token(Roles.ADMIN);
            case SERVICE -> TestJwtIssuer.token(Roles.SERVICE);
            default -> throw new IllegalArgumentException(caller);
        };
    }
}
