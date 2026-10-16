package com.mitpass.auth.config;

import com.mitpass.common.security.keys.KeyEncryptor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Clock (replaceable in tests), password hashing, signing-key encryption, and async execution for the mailer. */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** {@code {bcrypt}…} hashes; the prefix lets a later phase move to another algorithm without a reset. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Encrypts the RS256 private keys at rest ({@code mitpass.auth.key-secret}, D7). */
    @Bean
    KeyEncryptor keyEncryptor(AuthProperties properties) {
        return new KeyEncryptor(properties.keySecret());
    }
}
