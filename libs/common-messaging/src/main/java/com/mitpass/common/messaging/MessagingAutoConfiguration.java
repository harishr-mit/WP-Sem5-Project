package com.mitpass.common.messaging;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
}
