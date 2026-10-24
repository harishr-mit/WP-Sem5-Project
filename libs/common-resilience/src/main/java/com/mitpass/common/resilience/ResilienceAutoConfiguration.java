package com.mitpass.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Every RestClient built from Boot's {@code RestClient.Builder} gets {@link CircuitBreakerInterceptor}. The services
 * build their load-balanced builders from it too, so the breaker sees the service name before the load balancer
 * resolves it, and calls get Boot's tracing as well. Breaker states are exported as {@code resilience4j_circuitbreaker_*}
 * metrics for Grafana (D17).
 */
@AutoConfiguration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties properties) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .waitDurationInOpenState(properties.waitInOpenState())
                .build());
    }

    @Bean
    @ConditionalOnMissingBean
    CircuitBreakerInterceptor circuitBreakerInterceptor(
            CircuitBreakerRegistry breakers, ResilienceProperties properties) {
        return new CircuitBreakerInterceptor(breakers, properties.retryGets());
    }

    @Bean
    RestClientCustomizer circuitBreakerRestClientCustomizer(CircuitBreakerInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry breakers) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers);
    }
}
