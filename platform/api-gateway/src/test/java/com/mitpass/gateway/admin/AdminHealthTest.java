package com.mitpass.gateway.admin;

import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestReactiveSecurityConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/**
 * {@code GET /api/admin/health} (D15): every registered instance with its status and component names; one that
 * doesn't answer is DOWN with a reason, not an error. ADMIN only, at the gateway itself. The "registry" here has two
 * instances: the gateway under test (UP, real actuator) and a closed port (DOWN).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "mitpass.gateway.admin-health.timeout=1s"})
@AutoConfigureWebTestClient
@Import({TestReactiveSecurityConfig.class, AdminHealthTest.FakeRegistry.class})
class AdminHealthTest {

    @Autowired
    WebTestClient web;

    @TestConfiguration
    static class FakeRegistry {
        /** Not @Primary: Spring Cloud's composite client (primary) merges it, as it would merge Eureka's. */
        @Bean
        ReactiveDiscoveryClient fakeDiscovery(Environment env) {
            return new ReactiveDiscoveryClient() {
                @Override
                public String description() {
                    return "test registry";
                }

                @Override
                public Flux<ServiceInstance> getInstances(String serviceId) {
                    URI uri = "API-GATEWAY".equals(serviceId)
                            ? URI.create("http://localhost:" + env.getProperty("local.server.port"))
                            : URI.create("http://localhost:1");
                    return Flux.just(new DefaultServiceInstance(
                            serviceId + "-1", serviceId, uri.getHost(), uri.getPort(), false));
                }

                @Override
                public Flux<String> getServices() {
                    return Flux.just("API-GATEWAY", "PASS-SERVICE");
                }
            };
        }
    }

    @Test
    void anAdminSeesEveryInstanceUpOrDown() {
        web.get()
                .uri("/api/admin/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.ADMIN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].service")
                .isEqualTo("api-gateway")
                .jsonPath("$[0].status")
                .isEqualTo("UP")
                .jsonPath("$[0].components.ping")
                .isEqualTo("UP")
                .jsonPath("$[1].service")
                .isEqualTo("pass-service")
                .jsonPath("$[1].status")
                .isEqualTo("DOWN")
                .jsonPath("$[1].detail")
                .isEqualTo("not reachable");
    }

    @Test
    void otherRolesGetA403ProblemAndNoTokenA401() {
        web.get()
                .uri("/api/admin/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.ORGANIZER))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:mitpass:problem:forbidden");
        web.get()
                .uri("/api/admin/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.STUDENT))
                .exchange()
                .expectStatus()
                .isForbidden();
        web.get().uri("/api/admin/health").exchange().expectStatus().isUnauthorized();
    }
}
