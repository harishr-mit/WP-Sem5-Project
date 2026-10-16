package com.mitpass.checkin.config;

import com.mitpass.checkin.service.PassCopyReconciler;
import com.mitpass.common.pass.PassTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Clock (replaceable in tests), the pass-token verifier, properties, the reconciler's timers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckinProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The shared ES256 verifier from libs/common-pass (D12 contract). */
    @Bean
    PassTokenVerifier passTokenVerifier(CheckinProperties properties) {
        return new PassTokenVerifier(properties.passIssuer());
    }

    /**
     * The reconciler's jobs (docs/plans/phase-5.md §4a) at the configured intervals, starting shortly after startup.
     * Off in tests, which run the jobs themselves.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "mitpass.checkin.reconcile", name = "enabled", matchIfMissing = true)
    static class ReconcileSchedule implements SchedulingConfigurer {

        private final PassCopyReconciler reconciler;
        private final CheckinProperties properties;

        ReconcileSchedule(PassCopyReconciler reconciler, CheckinProperties properties) {
            this.reconciler = reconciler;
            this.properties = properties;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(new FixedDelayTask(
                    reconciler::scheduledRevocations,
                    properties.reconcile().revocationsInterval(),
                    Duration.ofSeconds(15)));
            registrar.addFixedDelayTask(new FixedDelayTask(
                    reconciler::scheduledWindow, properties.reconcile().windowInterval(), Duration.ofSeconds(20)));
        }
    }
}
