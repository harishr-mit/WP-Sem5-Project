package com.mitpass.common.security;

import com.mitpass.common.security.jwt.MitpassJwtAuthenticationConverter;
import com.mitpass.common.security.jwt.MitpassJwtDecoders;
import com.mitpass.common.security.web.CurrentUserArgumentResolver;
import com.mitpass.common.security.web.ProblemAccessDeniedHandler;
import com.mitpass.common.security.web.ProblemAuthenticationEntryPoint;
import com.mitpass.common.security.web.PublicPaths;
import com.mitpass.common.security.web.SecurityExceptionAdvice;
import com.mitpass.common.security.web.UserMdcFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes a servlet service an OAuth2 resource server just by depending on {@code common-security}
 * (docs/plans/phase-2.md §8):
 *
 * <ul>
 *   <li>stateless filter chain: {@code mitpass.security.public-paths} open, everything else needs a valid token;
 *   <li>tokens checked against the auth-service JWKS, fetched through Eureka;
 *   <li>{@code roles} claim → {@code ROLE_*}; {@code @PreAuthorize} enabled;
 *   <li>401/403 as ProblemDetail, {@code userId} in the MDC, {@link CurrentUser} controller parameters.
 * </ul>
 *
 * Every bean backs off when the service defines its own (auth-service brings its own {@link JwtDecoder}).
 */
@AutoConfiguration(
        beforeName = {
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
            "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
            "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MitpassSecurityProperties.class)
@EnableMethodSecurity
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain mitpassSecurityFilterChain(
            HttpSecurity http,
            MitpassSecurityProperties properties,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        requests -> requests.requestMatchers(PublicPaths.matchers(properties.getPublicPaths()))
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(new MitpassJwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(new UserMdcFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint() {
        return new ProblemAuthenticationEntryPoint();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAccessDeniedHandler problemAccessDeniedHandler() {
        return new ProblemAccessDeniedHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityExceptionAdvice securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    @Bean
    WebMvcConfigurer mitpassCurrentUserArgumentResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }

    /** The default decoder: auth-service's JWK set, through Eureka unless {@code load-balanced=false}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(JwtDecoder.class)
    static class JwtDecoderConfiguration {

        /** Not a default candidate: code asking for "a RestTemplate" must not get a load-balanced one. */
        @Bean(defaultCandidate = false)
        @LoadBalanced
        @ConditionalOnProperty(name = "mitpass.security.load-balanced", havingValue = "true", matchIfMissing = true)
        RestTemplate mitpassJwksRestTemplate() {
            return jwksClient();
        }

        @Bean
        JwtDecoder jwtDecoder(
                MitpassSecurityProperties properties, @LoadBalanced ObjectProvider<RestTemplate> loadBalanced) {
            RestTemplate client = properties.isLoadBalanced() ? loadBalanced.getObject() : jwksClient();
            return MitpassJwtDecoders.fromJwkSetUri(properties, client);
        }

        private static RestTemplate jwksClient() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(3));
            return new RestTemplate(factory);
        }
    }
}
