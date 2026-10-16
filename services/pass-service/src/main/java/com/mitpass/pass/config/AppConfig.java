package com.mitpass.pass.config;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.keys.KeyEncryptor;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Clock (replaceable in tests), key encryption, JPA auditing, properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PassProperties.class)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Encrypts the ES256 private keys at rest ({@code mitpass.passes.key-secret}, D12). */
    @Bean
    KeyEncryptor keyEncryptor(PassProperties properties) {
        return new KeyEncryptor(properties.keySecret());
    }

    /** The caller's user id; {@code "system"} for message consumers and the scheduler. */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(
                CurrentUser.current().map(user -> user.id().toString()).orElse("system"));
    }

    /** The pass lifecycle scheduler (unlock, expiry); off in tests, which call it directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "mitpass.passes.scheduler", name = "enabled", matchIfMissing = true)
    static class SchedulingConfig {}
}
