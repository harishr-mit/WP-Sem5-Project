package com.mitpass.checkin.config;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.client.HttpEventCatalog;
import com.mitpass.checkin.client.HttpPassDirectory;
import com.mitpass.checkin.client.PassDirectory;
import com.mitpass.checkin.client.PassJwksCache;
import com.mitpass.checkin.client.PassKeys;
import com.mitpass.checkin.client.ServiceTokenProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Calls to event-, pass- and auth-service through Eureka. The builder is {@code defaultCandidate = false} so the Eureka
 * client never gets a load-balanced builder by type (log.md: otherwise the service never registers). Tests override the
 * client beans with @Primary in-memory fakes.
 */
@Configuration(proxyBeanMethods = false)
public class ClientConfig {

    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory);
    }

    /** Public catalogue reads without a token; gates relay the caller's; assignments send the volunteer's own. */
    @Bean
    EventCatalog eventCatalog(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.event-service:http://event-service}") String baseUrl,
            CheckinProperties properties,
            Clock clock) {
        return new HttpEventCatalog(
                loadBalancedRestClientBuilder
                        .clone()
                        .baseUrl(baseUrl)
                        .requestInterceptor(new com.mitpass.common.security.client.BearerTokenRelayInterceptor())
                        .build(),
                properties.eventCacheTtl(),
                properties.assignmentCacheTtl(),
                properties.timeZone(),
                clock);
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.auth-service:http://auth-service}") String baseUrl,
            CheckinProperties properties,
            Clock clock) {
        return new ServiceTokenProvider(
                loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build(), properties.serviceClient(), clock);
    }

    /** The reconciler's view of pass-service: always with this service's own token, never a user's. */
    @Bean
    PassDirectory passDirectory(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.pass-service:http://pass-service}") String baseUrl,
            ServiceTokenProvider serviceTokens) {
        return new HttpPassDirectory(loadBalancedRestClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestInterceptor(serviceTokens)
                .build());
    }

    /** The pass JWK set is public. */
    @Bean
    PassKeys passKeys(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.pass-service:http://pass-service}") String baseUrl,
            CheckinProperties properties,
            Clock clock) {
        return new PassJwksCache(
                loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build(),
                properties.jwksRefetchMinInterval(),
                clock);
    }
}
