package com.mitpass.checkin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.config.CheckinProperties.ServiceClient;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.error.ProblemException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The HTTP clients against a mock server: service tokens, the pass key cache, event-service errors. */
class HttpClientsTest {

    final TestClock clock = new TestClock();

    @Test
    void theServiceTokenIsFetchedOnceAndSentWithEveryPassServiceCall() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl("http://auth-service");
        MockRestServiceServer auth = MockRestServiceServer.bindTo(authBuilder).build();
        auth.expect(ExpectedCount.once(), requestTo("http://auth-service/api/auth/service-token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.clientId").value("checkin-service"))
                .andExpect(jsonPath("$.clientSecret").value("s3cret"))
                .andRespond(withSuccess(
                        "{\"accessToken\":\"svc-token\",\"tokenType\":\"Bearer\",\"expiresIn\":300}",
                        MediaType.APPLICATION_JSON));
        ServiceTokenProvider tokens =
                new ServiceTokenProvider(authBuilder.build(), new ServiceClient("checkin-service", "s3cret"), clock);

        RestClient.Builder passBuilder =
                RestClient.builder().baseUrl("http://pass-service").requestInterceptor(tokens);
        MockRestServiceServer pass = MockRestServiceServer.bindTo(passBuilder).build();
        UUID id = UUID.randomUUID();
        pass.expect(requestTo("http://pass-service/api/passes/" + id))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withResourceNotFound());
        pass.expect(requestTo("http://pass-service/api/revocations?since=4&limit=500"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(
                        "{\"items\":[{\"seq\":5,\"passId\":\"" + id + "\",\"eventId\":7,"
                                + "\"revokedAt\":\"2026-11-14T03:00:00Z\",\"reason\":\"ORGANIZER\"}],\"nextSince\":5}",
                        MediaType.APPLICATION_JSON));
        HttpPassDirectory directory = new HttpPassDirectory(passBuilder.build());

        assertThat(directory.pass(id)).isEmpty();
        PassDirectory.RevocationPage page = directory.revocationsSince(4, 500);

        assertThat(page.items())
                .singleElement()
                .satisfies(item -> assertThat(item.passId()).isEqualTo(id));
        assertThat(page.nextSince()).isEqualTo(5);
        auth.verify();
        pass.verify();
    }

    @Test
    void theServiceTokenIsRenewedShortlyBeforeItExpires() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-service");
        MockRestServiceServer auth = MockRestServiceServer.bindTo(builder).build();
        auth.expect(ExpectedCount.twice(), requestTo("http://auth-service/api/auth/service-token"))
                .andRespond(withSuccess("{\"accessToken\":\"t\",\"expiresIn\":300}", MediaType.APPLICATION_JSON));
        ServiceTokenProvider tokens = new ServiceTokenProvider(builder.build(), new ServiceClient("c", "s"), clock);

        tokens.token();
        clock.advance(Duration.ofSeconds(269));
        tokens.token(); // still cached
        clock.advance(Duration.ofSeconds(2)); // 30 s before expiry: renew
        tokens.token();

        auth.verify();
    }

    @Test
    void anUnknownKidRefetchesThePassKeysAtMostOncePerInterval() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pass-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), requestTo("http://pass-service/.well-known/pass-jwks.json"))
                .andRespond(withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        PassJwksCache cache = new PassJwksCache(builder.build(), Duration.ofSeconds(30), clock);

        assertThat(cache.key("k1")).isPresent(); // first fetch
        assertThat(cache.key("k1")).isPresent(); // cached, no call
        assertThat(cache.key("forged")).isEmpty(); // refetch refused: within 30 s
        clock.advance(Duration.ofSeconds(31));
        assertThat(cache.key("forged")).isEmpty(); // second (and last) fetch

        server.verify();
    }

    @Test
    void eventServiceErrorsBecome404Or503() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://event-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://event-service/api/events/1")).andRespond(withResourceNotFound());
        server.expect(requestTo("http://event-service/api/events/2")).andRespond(withServerError());
        HttpEventCatalog catalog = new HttpEventCatalog(
                builder.build(), Duration.ofMinutes(1), Duration.ofSeconds(30), ZoneId.of("Asia/Kolkata"), clock);

        assertThatThrownBy(() -> catalog.event(1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> catalog.event(2))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("could not be read");
        server.verify();
    }
}
