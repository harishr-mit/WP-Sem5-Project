package com.mitpass.event.config;

import com.mitpass.common.security.client.BearerTokenRelayInterceptor;
import com.mitpass.common.security.client.ServiceTokenProvider;
import com.mitpass.event.client.AuthServiceClient;
import com.mitpass.event.client.CheckinClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Service-to-service REST. {@code http://auth-service} is a Eureka service id, resolved by Spring Cloud LoadBalancer
 * (the {@code @LoadBalanced} builder). Short timeouts; circuit breakers come in Phase 8.
 *
 * <p>The builder is {@code defaultCandidate = false}: injection by type alone (how the Eureka client asks for a
 * {@code RestClient.Builder}) must not get a load-balanced one, or Eureka tries to resolve {@code localhost:8761} as a
 * service id and the service never registers. It is injected here by qualifier.
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

    @Bean
    ServiceTokenProvider serviceTokenProvider(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.auth-service:http://auth-service}") String baseUrl,
            @Value("${mitpass.service-client.id:event-service}") String clientId,
            @Value("${mitpass.service-client.secret}") String secret,
            Clock clock) {
        return new ServiceTokenProvider(
                loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build(), clientId, secret, clock);
    }

    /** With this service's own token: the question is event-service's, not the organizer's (RL3). */
    @Bean
    CheckinClient checkinClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.checkin-service:http://checkin-service}") String baseUrl,
            ServiceTokenProvider serviceTokens) {
        return new CheckinClient(loadBalancedRestClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestInterceptor(serviceTokens)
                .build());
    }

    @Bean
    AuthServiceClient authServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.auth-service:http://auth-service}") String baseUrl) {
        return new AuthServiceClient(loadBalancedRestClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestInterceptor(new BearerTokenRelayInterceptor())
                .build());
    }
}
