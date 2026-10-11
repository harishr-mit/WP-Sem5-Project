package com.mitpass.event.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mitpass.common.security.client.BearerTokenRelayInterceptor;
import com.mitpass.common.security.jwt.MitpassJwtAuthenticationConverter;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ProblemException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The auth-service lookup: token relay and error translation. */
class AuthServiceClientTest {

    MockRestServiceServer server;
    AuthServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://auth-service")
                .requestInterceptor(new BearerTokenRelayInterceptor());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AuthServiceClient(builder.build());

        Jwt jwt = Jwt.withTokenValue("organizer-token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("ORGANIZER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new MitpassJwtAuthenticationConverter().convert(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysTheCallersTokenAndReadsTheAccount() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://auth-service/api/users/lookup")))
                .andExpect(queryParam("email", "karthik@gmail.com"))
                .andExpect(header("Authorization", "Bearer organizer-token"))
                .andRespond(withSuccess("""
                        {"id":"%s","fullName":"Karthik","email":"karthik@gmail.com","roles":["VOLUNTEER"]}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        AuthServiceClient.UserAccount account = client.findByEmail("karthik@gmail.com");

        assertThat(account.id()).isEqualTo(id);
        assertThat(account.roles()).containsExactly("VOLUNTEER");
        server.verify();
    }

    @Test
    void unknownEmailIsA422() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://auth-service/api/users/lookup")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.findByEmail("nobody@gmail.com"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nobody@gmail.com");
    }

    @Test
    void unreachableAuthServiceIsA503() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://auth-service/api/users/lookup")))
                .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.findByEmail("a@gmail.com"))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
