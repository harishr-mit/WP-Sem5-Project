package com.mitpass.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerApplicationTests {

    @LocalServerPort
    int port;

    @Test
    void servesTheEurekaRegistry() {
        String apps = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/eureka/apps")
                .header("Accept", "application/json")
                .retrieve()
                .body(String.class);

        assertThat(apps).contains("applications");
    }
}
