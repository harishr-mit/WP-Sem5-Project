package com.mitpass.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Paging, the exact time window and the live-only fallback of the event- and pass-service clients. */
class HttpClientsTest {

    @Test
    void registrationsAreReadPageByPageWithTheServiceToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://events");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String reg = UUID.randomUUID().toString();
        server.expect(requestTo("http://events/api/events/7/registrations?status=CONFIRMED&size=100&page=0"))
                .andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess(
                        page("{\"id\":\"" + reg + "\",\"userEmail\":\"a@x.test\"}", 2), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://events/api/events/7/registrations?status=CONFIRMED&size=100&page=1"))
                .andRespond(withSuccess(
                        page("{\"id\":\"" + UUID.randomUUID() + "\",\"userEmail\":\"b@x.test\"}", 2),
                        MediaType.APPLICATION_JSON));
        RestClient withToken = builder.requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth("svc");
                    return execution.execute(request, body);
                })
                .build();

        var events = new HttpClients.Events(withToken, withToken, Duration.ofSeconds(60));

        var found = events.registrations(7, "CONFIRMED");
        assertThat(found).extracting(EventDirectory.Recipient::userEmail).containsExactly("a@x.test", "b@x.test");
        assertThat(found.getFirst().id()).hasToString(reg);
        server.verify();
    }

    @Test
    void startingBetweenAsksADayWiderAndKeepsTheExactWindow() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://events");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://events/api/events?from=2026-11-13&to=2026-11-16&size=100&page=0"))
                .andRespond(withSuccess(
                        page(
                                "{\"id\":1,\"startsAt\":\"2026-11-14T10:00:00Z\"},"
                                        + "{\"id\":2,\"startsAt\":\"2026-11-15T10:00:00Z\"}",
                                1),
                        MediaType.APPLICATION_JSON));
        RestClient client = builder.build();

        var events = new HttpClients.Events(client, client, Duration.ofSeconds(60));

        assertThat(events.startingBetween(Instant.parse("2026-11-14T09:00:00Z"), Instant.parse("2026-11-15T09:00:00Z")))
                .containsExactly(1L);
        server.verify();
    }

    @Test
    void aLiveOnlyEventHasNoStaticCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://passes");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID pass = UUID.randomUUID();
        server.expect(requestTo("http://passes/api/passes/" + pass + "/qr/static?format=png"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(new HttpClients.Passes(builder.build()).staticQrPng(pass)).isEmpty();
        server.verify();
    }

    private static String page(String rows, int totalPages) {
        return "{\"content\":[" + rows + "],\"page\":0,\"size\":100,\"totalElements\":2,\"totalPages\":" + totalPages
                + "}";
    }
}
