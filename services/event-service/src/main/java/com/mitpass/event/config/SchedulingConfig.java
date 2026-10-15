package com.mitpass.event.config;

import com.mitpass.event.idempotency.IdempotencyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} (the lifecycle job, idempotency cleanup) and binds the scheduler settings. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({LifecycleSchedulerProperties.class, IdempotencyProperties.class})
public class SchedulingConfig {}
