package com.mitpass.checkin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinTestConfig;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.TestcontainersConfiguration;
import com.mitpass.security.test.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** Actuator is served only on the management port, metrics without a token (Prometheus scrapes it; D17). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, CheckinTestConfig.class, ScanScenario.class})
class ManagementPortTest {

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    final RestClient http = RestClient.create();

    @Test
    void metricsAndProbesAnswerOnTheManagementPortOnly() {
        assertThat(http.get()
                        .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                        .retrieve()
                        .body(String.class))
                .contains("jvm_memory_used_bytes");
        assertThat(status("http://localhost:" + managementPort + "/actuator/health/readiness"))
                .isEqualTo(200);
        assertThat(status("http://localhost:" + port + "/actuator/health")).isEqualTo(404);
    }

    private int status(String url) {
        return http.get()
                .uri(url)
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
