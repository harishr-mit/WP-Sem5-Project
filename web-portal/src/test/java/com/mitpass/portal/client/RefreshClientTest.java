package com.mitpass.portal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@code POST /api/auth/refresh} (plan §7): no bearer token; a refused refresh token means the session is over. */
class RefreshClientTest {

    MockRestServiceServer server;
    RefreshClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RefreshClient(builder, "http://gateway.test");
    }

    @Test
    void sendsTheRefreshTokenAndReadsTheNewPair() {
        server.expect(requestTo("http://gateway.test/api/auth/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(content().json("{\"refreshToken\":\"r-1\"}"))
                .andRespond(withSuccess(
                        "{\"accessToken\":\"a-2\",\"expiresIn\":900,\"refreshToken\":\"r-2\"}",
                        MediaType.APPLICATION_JSON));

        var tokens = client.refresh("r-1");

        assertThat(tokens.accessToken()).isEqualTo("a-2");
        assertThat(tokens.refreshToken()).isEqualTo("r-2");
    }

    @Test
    void aRefusedRefreshTokenEndsTheSession() {
        server.expect(requestTo("http://gateway.test/api/auth/refresh"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:mitpass:problem:refresh-token-reused\",\"status\":401}"));

        assertThatThrownBy(() -> client.refresh("r-1")).isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void anOutageIsNotALogout() {
        server.expect(requestTo("http://gateway.test/api/auth/refresh")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.refresh("r-1"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(503));
    }
}
