package com.mitpass.notification.config;

import com.mitpass.common.security.client.ServiceTokenProvider;
import com.mitpass.notification.client.EventDirectory;
import com.mitpass.notification.client.HttpClients;
import com.mitpass.notification.client.PassMedia;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Clock, ShedLock and the clients for event-, pass- and auth-service through Eureka. The builder is
 * {@code defaultCandidate = false} so the Eureka client never picks a load-balanced builder by type (log.md: otherwise
 * the service never registers). Tests replace the clients with @Primary fakes.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** {@code usingDbTime()}: instances with different clocks still agree on who holds the lock. */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClient.Builder boot) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        // Boot's (prototype) builder: tracing and the circuit breaker (common-resilience) come with it (D17).
        return boot.requestFactory(factory);
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.auth-service:http://auth-service}") String baseUrl,
            NotificationProperties properties,
            Clock clock) {
        return new ServiceTokenProvider(
                loadBalancedRestClientBuilder.clone().baseUrl(baseUrl).build(),
                properties.serviceClient().id(),
                properties.serviceClient().secret(),
                clock);
    }

    @Bean
    EventDirectory eventDirectory(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.event-service:http://event-service}") String baseUrl,
            ServiceTokenProvider serviceTokens,
            NotificationProperties properties) {
        RestClient.Builder events = loadBalancedRestClientBuilder.clone().baseUrl(baseUrl);
        return new HttpClients.Events(
                events.clone().build(),
                events.clone().requestInterceptor(serviceTokens).build(),
                properties.catalogCacheTtl());
    }

    @Bean
    PassMedia passMedia(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mitpass.clients.pass-service:http://pass-service}") String baseUrl,
            ServiceTokenProvider serviceTokens) {
        return new HttpClients.Passes(loadBalancedRestClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestInterceptor(serviceTokens)
                .build());
    }
}
