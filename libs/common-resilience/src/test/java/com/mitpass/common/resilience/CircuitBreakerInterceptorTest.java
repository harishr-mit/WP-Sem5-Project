package com.mitpass.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class CircuitBreakerInterceptorTest {

    static final String URL = "http://pass-service/api/passes/1";

    CircuitBreakerRegistry breakers;
    MockRestServiceServer server;
    RestClient rest;

    @BeforeEach
    void setUp() {
        breakers = new ResilienceAutoConfiguration()
                .circuitBreakerRegistry(new ResilienceProperties(50, 10, 5, Duration.ofMinutes(1), true));
        RestClient.Builder builder =
                RestClient.builder().requestInterceptor(new CircuitBreakerInterceptor(breakers, true));
        server = MockRestServiceServer.bindTo(builder).build();
        rest = builder.build();
    }

    @Test
    void serverErrorsOpenTheBreakerAndThenCallsFailWithoutReachingTheTarget() {
        server.expect(times(5), requestTo(URL)).andRespond(withServiceUnavailable());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> get()).isInstanceOf(HttpServerErrorException.class);
        }
        assertThat(breakers.circuitBreaker("pass-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> get())
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("circuit breaker for pass-service is open");
        server.verify(); // exactly the five calls reached the target
    }

    @Test
    void clientErrorsAreNormalAnswersAndKeepTheBreakerClosed() {
        server.expect(times(10), requestTo(URL)).andRespond(withResourceNotFound());
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> get()).isInstanceOf(HttpClientErrorException.NotFound.class);
        }
        assertThat(breakers.circuitBreaker("pass-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aGetIsTriedOnceMoreAfterAnIoError() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        assertThat(get()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void aPostIsNeverRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        assertThatThrownBy(() -> rest.post().uri(URL).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }

    @Test
    void targetIsTheServiceNameOrHostAndPort() {
        assertThat(CircuitBreakerInterceptor.target(java.net.URI.create("http://pass-service/x")))
                .isEqualTo("pass-service");
        assertThat(CircuitBreakerInterceptor.target(java.net.URI.create("http://localhost:8080/x")))
                .isEqualTo("localhost:8080");
    }

    private String get() {
        return rest.get().uri(URL).retrieve().body(String.class);
    }
}
