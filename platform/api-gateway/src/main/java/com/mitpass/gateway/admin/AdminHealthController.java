package com.mitpass.gateway.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * The admin's health overview (owner decision Q3, docs/DECISIONS.md D15): every instance registered in Eureka and its
 * {@code /actuator/health} (status and component names; details stay hidden). Served by the gateway so the portal
 * keeps calling only the gateway (D9). ADMIN only (EdgeSecurityConfig). An instance that doesn't answer within
 * {@code mitpass.gateway.admin-health.timeout} is reported DOWN, so one dead service can't slow the page down.
 */
@RestController
@EnableConfigurationProperties(AdminHealthController.Properties.class)
public class AdminHealthController {

    /** {@code mitpass.gateway.admin-health.*}. */
    @ConfigurationProperties("mitpass.gateway.admin-health")
    public record Properties(@DefaultValue("2s") Duration timeout) {}

    /** One instance's health. {@code detail} explains a DOWN that has no health body (timeout, refused). */
    public record InstanceHealth(
            String service,
            String instanceId,
            String url,
            String status,
            Map<String, String> components,
            String detail,
            Instant checkedAt) {}

    private final ReactiveDiscoveryClient discovery;
    private final WebClient http;
    private final Properties properties;
    private final Clock clock;

    @Autowired
    public AdminHealthController(ReactiveDiscoveryClient discovery, Properties properties) {
        this(discovery, WebClient.create(), properties, Clock.systemUTC());
    }

    AdminHealthController(ReactiveDiscoveryClient discovery, WebClient http, Properties properties, Clock clock) {
        this.discovery = discovery;
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping(value = "/api/admin/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<InstanceHealth>> health() {
        return discovery
                .getServices()
                .flatMap(discovery::getInstances)
                .flatMap(this::check)
                .sort(Comparator.comparing(InstanceHealth::service).thenComparing(InstanceHealth::instanceId))
                .collectList();
    }

    private Mono<InstanceHealth> check(ServiceInstance instance) {
        String url = instance.getUri().toString();
        return http.get()
                .uri(url + "/actuator/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(
                        response -> response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .defaultIfEmpty(Map.of()))
                .timeout(properties.timeout())
                .map(body -> health(instance, url, body, null))
                .onErrorResume(ex -> Mono.just(health(instance, url, Map.of("status", "DOWN"), reason(ex))));
    }

    @SuppressWarnings("unchecked")
    private InstanceHealth health(ServiceInstance instance, String url, Map<String, Object> body, String detail) {
        Map<String, String> components = new LinkedHashMap<>();
        if (body.get("components") instanceof Map<?, ?> parts) {
            ((Map<String, Object>) parts).forEach((name, value) -> {
                Object status = value instanceof Map<?, ?> part ? part.get("status") : null;
                components.put(name, status == null ? "UNKNOWN" : status.toString());
            });
        }
        Object status = body.get("status");
        return new InstanceHealth(
                instance.getServiceId().toLowerCase(java.util.Locale.ROOT),
                instance.getInstanceId() != null ? instance.getInstanceId() : url,
                url,
                status == null ? "UNKNOWN" : status.toString(),
                components,
                detail,
                clock.instant());
    }

    private String reason(Throwable ex) {
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return "no answer in " + properties.timeout().toSeconds() + " s";
        }
        if (ex instanceof WebClientResponseException http) {
            return "HTTP " + http.getStatusCode().value();
        }
        return "not reachable";
    }
}
