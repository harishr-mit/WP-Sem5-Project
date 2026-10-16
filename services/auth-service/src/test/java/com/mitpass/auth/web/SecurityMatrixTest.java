package com.mitpass.auth.web;

import static com.mitpass.security.test.SecurityMatrix.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.TestAccounts;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.auth.service.AccessTokenService;
import com.mitpass.security.test.SecurityMatrix;
import com.mitpass.security.test.SecurityMatrix.Rule;
import java.nio.file.Path;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every auth-service endpoint × every kind of caller. Callers are real accounts with tokens minted by auth-service
 * itself (it trusts only its own keys). Writes {@code target/security-matrix/auth-service.md}.
 */
@AuthIntegrationTest
class SecurityMatrixTest {

    static final String STUDENT = "student";
    static final String VOLUNTEER = "volunteer";
    static final String ORGANIZER = "organizer";
    static final String ADMIN = "admin";

    /** {@code self} = the student's own id; {@code target} = a fresh account per request. */
    record Ids(UUID self, String selfEmail, UUID target) {}

    record Endpoint(
            HttpMethod method, String template, Function<Ids, String> path, Function<Ids, String> body, Rule rule) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    UserRepository users;

    @Autowired
    AccessTokenService accessTokens;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        Map<String, User> callers = new LinkedHashMap<>();
        callers.put(STUDENT, accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT));
        callers.put(VOLUNTEER, accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT, RoleName.VOLUNTEER));
        callers.put(ORGANIZER, accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ORGANIZER));
        callers.put(ADMIN, accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ADMIN));
        User student = callers.get(STUDENT);

        List<Endpoint> endpoints = List.of(
                post("/api/auth/signup", ids -> signup(), Rule.publicEndpoint()),
                get(
                        "/api/auth/verify-email?token=…",
                        ids -> "/api/auth/verify-email?token=not-a-real-token",
                        Rule.publicEndpoint()),
                post(
                        "/api/auth/verify-email/resend",
                        ids -> "{\"email\":\"" + ids.selfEmail() + "\"}",
                        Rule.publicEndpoint()),
                post(
                        "/api/auth/login",
                        ids -> "{\"email\":\"%s\",\"password\":\"%s\"}"
                                .formatted(ids.selfEmail(), TestAccounts.PASSWORD),
                        Rule.publicEndpoint()),
                post("/api/auth/refresh", ids -> "{\"refreshToken\":\"not-a-real-token\"}", Rule.publicEndpoint()),
                post("/api/auth/logout", ids -> "{\"refreshToken\":\"not-a-real-token\"}", Rule.publicEndpoint()),
                post(
                        "/api/auth/service-token",
                        ids -> "{\"clientId\":\"checkin-service\",\"clientSecret\":\"wrong\"}",
                        Rule.publicEndpoint()),
                get("/.well-known/jwks.json", ids -> "/.well-known/jwks.json", Rule.publicEndpoint()),
                get("/api/departments", ids -> "/api/departments", Rule.publicEndpoint()),
                get("/api/users/me", ids -> "/api/users/me", Rule.authenticated()),
                new Endpoint(
                        HttpMethod.DELETE,
                        "/api/users/me/sessions",
                        ids -> "/api/users/me/sessions",
                        ids -> null,
                        Rule.authenticated()),
                get("/api/users", ids -> "/api/users", Rule.only(ADMIN)),
                get("/api/users/{own id}", ids -> "/api/users/" + ids.self(), Rule.only(STUDENT, ADMIN)),
                get(
                        "/api/users/lookup?email=…",
                        ids -> "/api/users/lookup?email=" + ids.selfEmail(),
                        Rule.only(ORGANIZER, ADMIN)),
                new Endpoint(
                        HttpMethod.PUT,
                        "/api/users/{id}/roles",
                        ids -> "/api/users/" + ids.target() + "/roles",
                        ids -> "{\"roles\":[\"STUDENT\",\"VOLUNTEER\"]}",
                        Rule.only(ADMIN)),
                new Endpoint(
                        HttpMethod.POST,
                        "/api/users/{id}/unlock",
                        ids -> "/api/users/" + ids.target() + "/unlock",
                        ids -> null,
                        Rule.only(ADMIN)),
                new Endpoint(
                        HttpMethod.DELETE,
                        "/api/users/{id}/sessions",
                        ids -> "/api/users/" + ids.target() + "/sessions",
                        ids -> null,
                        Rule.only(ADMIN)),
                new Endpoint(
                        HttpMethod.POST,
                        "/api/auth/keys/rotate",
                        ids -> "/api/auth/keys/rotate",
                        ids -> null,
                        Rule.only(ADMIN)));

        List<String> columns = List.of(ANONYMOUS, STUDENT, VOLUNTEER, ORGANIZER, ADMIN);
        SecurityMatrix matrix = new SecurityMatrix("auth-service", columns);
        for (Endpoint endpoint : endpoints) {
            for (String caller : columns) {
                Ids ids = new Ids(
                        student.getId(),
                        student.getEmail(),
                        accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT)
                                .getId());
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                        endpoint.method(), endpoint.path().apply(ids));
                String body = endpoint.body().apply(ids);
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).content(body);
                }
                if (!ANONYMOUS.equals(caller)) {
                    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(callers.get(caller)));
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
        matrix.writeTo(Path.of("target", "security-matrix", "auth-service.md"));
        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(endpoints.size() * columns.size());
    }

    /** A fresh token each time: the key may have been rotated by an earlier cell. */
    private String tokenOf(User user) {
        return transaction.execute(status -> accessTokens
                .mint(users.findWithRolesById(user.getId()).orElseThrow())
                .value());
    }

    private static Endpoint get(String template, Function<Ids, String> path, Rule rule) {
        return new Endpoint(HttpMethod.GET, template, path, ids -> null, rule);
    }

    private static Endpoint post(String path, Function<Ids, String> body, Rule rule) {
        return new Endpoint(HttpMethod.POST, path, ids -> path, body, rule);
    }

    private static String signup() {
        return """
                {"fullName":"Matrix User","email":"%s","password":"Techfest2026","department":"CSE","year":2}
                """.formatted(TestAccounts.uniqueGmail());
    }
}
