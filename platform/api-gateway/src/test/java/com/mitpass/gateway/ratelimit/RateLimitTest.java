package com.mitpass.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestReactiveSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Login is limited per browser IP and scans per scanner, in Redis (D17); over the limit the answer is a 429 problem
 * with Retry-After. Small buckets here (2 requests each); no service runs behind the routes, so allowed requests get
 * the gateway's 503 for a missing instance, which is enough to tell them from a 429.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "eureka.client.enabled=false",
            "mitpass.gateway.rate-limit.login.burst-capacity=12",
            "mitpass.gateway.rate-limit.scans.burst-capacity=2",
            "mitpass.gateway.rate-limit.scans.replenish-rate=1"
        })
@AutoConfigureWebTestClient
@Import({TestReactiveSecurityConfig.class, RateLimitTest.Redis.class})
class RateLimitTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Redis {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        }
    }

    @Autowired
    WebTestClient web;

    @Test
    void loginIsLimitedPerBrowserIp() {
        assertThat(login("10.0.0.1").getStatus().value()).isNotEqualTo(429);
        assertThat(login("10.0.0.1").getStatus().value()).isNotEqualTo(429);

        web.post()
                .uri("/api/auth/login")
                .header("X-Forwarded-For", "10.0.0.1, 172.18.0.5")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "6")
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:mitpass:problem:too-many-requests")
                .jsonPath("$.status")
                .isEqualTo(429);

        assertThat(login("10.0.0.2").getStatus().value()).as("another browser").isNotEqualTo(429);
    }

    @Test
    void scansAreLimitedPerScanner() {
        String one = TestJwtIssuer.token(Roles.VOLUNTEER);
        String other = TestJwtIssuer.token(Roles.VOLUNTEER);
        assertThat(scan(one).getStatus().value()).isNotEqualTo(429);
        assertThat(scan(one).getStatus().value()).isNotEqualTo(429);
        assertThat(scan(one).getStatus().value()).isEqualTo(429);
        assertThat(scan(other).getStatus().value()).as("another scanner").isNotEqualTo(429);
    }

    private org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> login(String ip) {
        return web.post()
                .uri("/api/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectBody()
                .returnResult();
    }

    private org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> scan(String token) {
        return web.post()
                .uri("/api/scans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectBody()
                .returnResult();
    }
}
