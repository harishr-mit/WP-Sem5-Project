package com.mitpass.gateway.security;

import com.mitpass.common.security.MitpassSecurityProperties;
import com.mitpass.common.security.jwt.MitpassJwtAuthenticationConverter;
import com.mitpass.common.security.jwt.MitpassJwtValidators;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The edge pre-check (docs/plans/phase-2.md §9): public paths pass, everything else needs a valid access token, checked
 * against auth-service's JWK set (fetched through Eureka). Role rules are left to the services, which verify the token
 * again. CORS lives here too, so even a 401 from the edge carries the CORS headers a browser needs to read it.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties({
    MitpassSecurityProperties.class,
    CorsProperties.class,
    InternalPathsWebFilter.Properties.class
})
public class EdgeSecurityConfig {

    @Bean
    SecurityWebFilterChain edgeSecurity(
            ServerHttpSecurity http,
            MitpassSecurityProperties properties,
            ProblemServerAuthenticationEntryPoint entryPoint) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    for (String spec : properties.getPublicPaths()) {
                        String[] parts = spec.trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            exchanges
                                    .pathMatchers(HttpMethod.valueOf(parts[0]), parts[1])
                                    .permitAll();
                        } else {
                            exchanges.pathMatchers(parts[0]).permitAll();
                        }
                    }
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(new MitpassJwtAuthenticationConverter())))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    @Bean
    ProblemServerAuthenticationEntryPoint problemServerAuthenticationEntryPoint() {
        return new ProblemServerAuthenticationEntryPoint();
    }

    /**
     * Resolves {@code http://auth-service/...} through Eureka. Used only to fetch the JWK set; not a default candidate,
     * so nothing that asks for "a WebClient.Builder" by type gets a load-balanced one.
     */
    @Bean(defaultCandidate = false)
    @LoadBalanced
    WebClient.Builder jwksWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(
            MitpassSecurityProperties properties, @LoadBalanced WebClient.Builder jwksWebClientBuilder) {
        WebClient client = properties.isLoadBalanced() ? jwksWebClientBuilder.build() : WebClient.create();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(
                        properties.getJwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .webClient(client)
                .build();
        decoder.setJwtValidator(MitpassJwtValidators.create(properties));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("Location", "X-Correlation-Id", "Retry-After", "WWW-Authenticate"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
