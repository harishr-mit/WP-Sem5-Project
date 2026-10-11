package com.mitpass.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

@SpringBootTest(properties = "eureka.client.enabled=false")
class ApiGatewayApplicationTests {

    @Autowired
    RouteLocator routes;

    @Test
    void serviceRoutesAreConfigured() {
        assertThat(routes.getRoutes().map(Route::getId).collectList().block())
                .contains("auth-service", "auth-service-openapi", "event-service", "event-service-openapi");
    }
}
