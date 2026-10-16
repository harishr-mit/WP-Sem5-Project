package com.mitpass.common.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Declares a consuming service's inbox (docs/DECISIONS.md D13): a durable queue bound to the given routing keys on
 * {@code mitpass.events}, and its dead-letter partner {@code <queue>.dlq}. A message the listener rejects without
 * requeue (after its retries) goes through the {@code mitpass.events.dlx} direct exchange to that DLQ.
 *
 * <p><b>Minimal DLQ, pulled forward from P7 (owner decision 2026-10-16); P7 completes it</b> with inspect/replay
 * tooling and an alert when a DLQ isn't empty.
 */
public final class InboxQueues {

    private InboxQueues() {}

    /** Dead-letter queue name for an inbox. */
    public static String deadLetterQueue(String inbox) {
        return inbox + ".dlq";
    }

    /** Return this as a {@code @Bean}: RabbitAdmin declares everything when the first connection opens. */
    public static Declarables inbox(MessagingProperties properties, String queueName, String... routingKeys) {
        TopicExchange events = new TopicExchange(properties.exchange(), true, false);
        DirectExchange deadLetters = new DirectExchange(properties.consumer().deadLetterExchange(), true, false);
        String dlqName = deadLetterQueue(queueName);

        Queue inbox = QueueBuilder.durable(queueName)
                .deadLetterExchange(deadLetters.getName())
                .deadLetterRoutingKey(dlqName)
                .build();
        Queue dlq = QueueBuilder.durable(dlqName).build();

        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters, inbox, dlq));
        declarables.add(BindingBuilder.bind(dlq).to(deadLetters).with(dlqName));
        for (String key : routingKeys) {
            Binding binding = BindingBuilder.bind(inbox).to(events).with(key);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }
}
