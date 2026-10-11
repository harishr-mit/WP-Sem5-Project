package com.mitpass.security.test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.common.security.Roles;
import com.mitpass.common.web.CommonWebAutoConfiguration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * common-security's filter chain end to end (in a web slice): public paths, every way a token can be bad, method
 * security as 403 (not 500), {@code CurrentUser} and the {@code userId} MDC entry.
 */
@WebMvcTest(controllers = ProbeController.class, properties = "mitpass.security.public-paths=GET /public/**")
@Import({TestSecurityConfig.class, CommonWebAutoConfiguration.class})
class ResourceServerAutoConfigurationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void publicPathNeedsNoToken() throws Exception {
        mvc.perform(get("/public/ping")).andExpect(status().isOk());
    }

    @Test
    void missingTokenIsA401Problem() throws Exception {
        mvc.perform(get("/api/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/private"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, bearer(TestJwtIssuer.token(Roles.STUDENT))))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> badTokens() {
        String good = TestJwtIssuer.token(Roles.STUDENT);
        return Stream.of(
                Arguments.of("expired", TestJwtIssuer.expired(Roles.STUDENT)),
                Arguments.of("forged payload", TestJwtIssuer.forged(good)),
                Arguments.of("wrong issuer", TestJwtIssuer.wrongIssuer(Roles.STUDENT)),
                Arguments.of("wrong audience", TestJwtIssuer.wrongAudience(Roles.STUDENT)),
                Arguments.of("untrusted key", TestJwtIssuer.signedByOtherKey(Roles.ADMIN)),
                Arguments.of("alg none", TestJwtIssuer.algNone(Roles.ADMIN)),
                Arguments.of("garbage", "not-a-jwt"));
    }

    @ParameterizedTest(name = "{0} → 401")
    @MethodSource("badTokens")
    void badTokensAreRejectedWith401(String kind, String token) throws Exception {
        mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:unauthorized"));
    }

    @Test
    void forgedAdminTokenCannotReachAdminEndpoint() throws Exception {
        String forged = TestJwtIssuer.forged(TestJwtIssuer.token(Roles.STUDENT));
        mvc.perform(get("/api/admin").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongRoleIsA403ProblemNotA500() throws Exception {
        mvc.perform(get("/api/admin").header(HttpHeaders.AUTHORIZATION, bearer(TestJwtIssuer.token(Roles.STUDENT))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:forbidden"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void rightRolePasses() throws Exception {
        mvc.perform(get("/api/admin").header(HttpHeaders.AUTHORIZATION, bearer(TestJwtIssuer.token(Roles.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void currentUserAndMdcComeFromTheToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtIssuer.tokenFor(id, Roles.ORGANIZER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.roles", hasItem("ORGANIZER")))
                .andExpect(jsonPath("$.mdcUserId").value(id.toString()));
    }

    @Test
    void actuatorHealthAndSwaggerStayPublic() throws Exception {
        // No such endpoints in this slice: a 404 (not a 401) proves the path got past security.
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
