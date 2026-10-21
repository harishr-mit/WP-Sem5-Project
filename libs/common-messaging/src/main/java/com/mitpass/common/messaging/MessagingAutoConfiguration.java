package com.mitpass.common.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registered in {@code META-INF/spring/…AutoConfiguration.imports}: a service gets the outbox publisher, the relay and
 * the {@code mitpass.events} exchange by depending on {@code common-messaging} and creating the
 * {@code outbox_message} table (docs/DECISIONS.md D11).
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
        })
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    OutboxPublisher outboxPublisher(
            DataSource dataSource, ObjectProvider<JsonMapper> json, ObjectProvider<Clock> clock) {
        return new OutboxPublisher(
                JdbcClient.create(dataSource),
                json.getIfAvailable(() -> JsonMapper.builder().build()),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "mitpass.messaging.relay", name = "enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            ObjectProvider<Clock> clock) {
        return new OutboxRelay(
                JdbcClient.create(dataSource),
                new TransactionTemplate(transactionManager),
                rabbitTemplate,
                properties,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    EnvelopeReader envelopeReader(ObjectProvider<JsonMapper> json) {
        return new EnvelopeReader(json.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    /** Needs the {@code processed_message} table (reference DDL {@code mitpass/messaging/processed-message-…sql}). */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    IdempotentConsumer idempotentConsumer(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            EnvelopeReader envelopeReader,
            MessagingProperties properties,
            ObjectProvider<Clock> clock) {
        return new IdempotentConsumer(
                JdbcClient.create(dataSource),
                new TransactionTemplate(transactionManager),
                envelopeReader,
                clock.getIfAvailable(Clock::systemUTC),
                properties.consumer().retention());
    }

    /** Declared on the broker when the first connection opens (RabbitAdmin), so it exists before any consumer. */
    @Bean
    TopicExchange mitpassEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mitpass.messaging.tap-queue", name = "enabled")
    Queue mitpassTapQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.tapQueue().name())
                .withArguments(Map.of("x-max-length", properties.tapQueue().maxLength()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mitpass.messaging.tap-queue", name = "enabled")
    Binding mitpassTapBinding(Queue mitpassTapQueue, TopicExchange mitpassEventsExchange) {
        return BindingBuilder.bind(mitpassTapQueue).to(mitpassEventsExchange).with("#");
    }

    /** One per consuming service: the inboxes are the {@code .dlq} partners declared through {@link InboxQueues}. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Declarables.class)
    DeadLetters deadLetters(ConnectionFactory connectionFactory, ObjectProvider<Declarables> declarables) {
        List<String> inboxes = declarables
                .orderedStream()
                .flatMap(d -> d.getDeclarablesByType(Queue.class).stream())
                .map(Queue::getName)
                .filter(name -> name.endsWith(".dlq"))
                .map(name -> name.substring(0, name.length() - ".dlq".length()))
                .toList();
        return new DeadLetters(connectionFactory, inboxes, 1000);
    }

    /** Only where method security can guard it: without it the ADMIN rule would be silently ignored. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.security.access.prepost.PreAuthorize")
    @ConditionalOnBean(Declarables.class) // as deadLetters(): member classes are evaluated before the outer beans
    static class DeadLetterWeb {
        @Bean
        DeadLetterController deadLetterController(DeadLetters deadLetters) {
            return new DeadLetterController(deadLetters);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    @ConditionalOnBean(Declarables.class) // as deadLetters(): member classes are evaluated before the outer beans
    static class DeadLetterHealth {
        static final Status WARNING = new Status("WARNING", "A dead-letter queue holds messages");

        /** Component {@code deadLetters}: WARNING (HTTP 200, amber on the admin page) while a DLQ holds messages. */
        @Bean
        HealthIndicator deadLettersHealthIndicator(DeadLetters deadLetters) {
            return () -> {
                try {
                    long total = deadLetters.depths().stream()
                            .mapToLong(DeadLetters.QueueDepth::messages)
                            .sum();
                    return (total == 0 ? Health.up() : Health.status(WARNING))
                            .withDetail("messages", total)
                            .build();
                } catch (RuntimeException e) {
                    return Health.unknown().withException(e).build(); // the rabbit component already says DOWN
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(Declarables.class) // as deadLetters(): member classes are evaluated before the outer beans
    static class DeadLetterMetrics {
        @Bean
        MeterBinder deadLetterGauges(DeadLetters deadLetters) {
            return registry -> deadLetters
                    .inboxes()
                    .forEach(inbox -> Gauge.builder("mitpass.dlq.messages", () -> {
                                try {
                                    return deadLetters.count(inbox);
                                } catch (RuntimeException e) {
                                    return Double.NaN;
                                }
                            })
                            .tag("queue", InboxQueues.deadLetterQueue(inbox))
                            .register(registry));
        }
    }
}
