package com.mitpass.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.auth.AuthIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Service tokens for background jobs that have no user to relay (docs/plans/phase-5.md §4b, D7 exception): a
 * configured client id + secret gets a 5 min token with role SERVICE, accepted by every service's validators.
 */
@AuthIntegrationTest
class ServiceTokenTest {

    static final String CLIENT = "checkin-service";
    static final String SECRET = "dev-only-checkin-secret"; // application.yml dev default

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder decoder;

    @Test
    void aConfiguredClientGetsAShortLivedServiceToken() throws Exception {
        String body = mvc.perform(post("/api/auth/service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(CLIENT, SECRET)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The same decoder (and validators: iss, aud, exp) every service uses.
        Jwt jwt = decoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo("00000000-0000-4000-8000-0000000000f1");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(jwt.getClaimAsString("name")).isEqualTo(CLIENT);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aWrongSecretOrAnUnknownClientIs401WithTheSameAnswer() throws Exception {
        for (String body : new String[] {request(CLIENT, "wrong"), request("event-service", SECRET)}) {
            mvc.perform(post("/api/auth/service-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:mitpass:problem:invalid-client"));
        }
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mvc.perform(post("/api/auth/service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static String request(String client, String secret) {
        return "{\"clientId\":\"%s\",\"clientSecret\":\"%s\"}".formatted(client, secret);
    }
}
