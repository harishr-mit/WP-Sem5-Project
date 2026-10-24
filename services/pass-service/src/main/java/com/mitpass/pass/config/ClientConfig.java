package com.mitpass.pass.config;

import com.mitpass.pass.client.EventCatalogClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Calls to event-service through Eureka ({@code http://event-service}). The builder is {@code defaultCandidate = false}
 * so the Eureka client never gets a load-balanced builder by type (log.md: otherwise the service never registers).
 */
@Configuration(proxyBeanMethods = false)
public class ClientConfig {

    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClient.Builder boot) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        // Boot's (prototype) builder: tracing and the circuit breaker (common-resilience) come with it (D17).
        return boot.requestFactory(factory);
    }

    /** The event detail is public, so no token is relayed. */
    @Bean
    EventCatalogClient eventCatalogClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.event-service:http://event-service}") String baseUrl,
            PassProperties properties,
            Clock clock) {
        return new EventCatalogClient(
                loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build(), properties.eventCacheTtl(), clock);
    }
}
