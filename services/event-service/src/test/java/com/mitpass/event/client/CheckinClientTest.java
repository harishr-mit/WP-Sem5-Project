package com.mitpass.event.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mitpass.common.web.error.ProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CheckinClientTest {

    static final String URL = "http://checkin-service/api/attendance/events/7/summary";

    MockRestServiceServer server;
    CheckinClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://checkin-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CheckinClient(builder.build());
    }

    @Test
    void aCheckInCountAboveZeroMeansYes() {
        server.expect(requestTo(URL))
                .andRespond(
                        withSuccess("{\"eventId\":7,\"checkedIn\":3,\"validPasses\":10}", MediaType.APPLICATION_JSON));
        assertThat(client.hasCheckIns(7)).isTrue();
    }

    @Test
    void anEventCheckinServiceHasNeverSeenHasNone() {
        server.expect(requestTo(URL)).andRespond(withResourceNotFound());
        assertThat(client.hasCheckIns(7)).isFalse();
    }

    @Test
    void noAnswerIsA503NotAGuess() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.hasCheckIns(7))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(503));
    }
}
