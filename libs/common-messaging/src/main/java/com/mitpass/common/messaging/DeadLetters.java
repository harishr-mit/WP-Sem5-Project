package com.mitpass.common.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/**
 * Inspect, replay and discard the messages in this service's dead-letter queues (D13, completed in P7).
 *
 * <p>Messages are read with {@code basicGet} and no ack; everything not acted on is nacked back with requeue, so a peek
 * never loses a message (the DLQ order may change, which doesn't matter there). A replay publishes the message to its
 * inbox with the same {@code messageId}, waits for the broker's confirm and only then acks the DLQ copy: the
 * idempotent consumer still applies it at most once.
 */
public class DeadLetters {

    /** What a dead-lettered message looks like to an admin. The cause is in the consumer's log (Spring retry). */
    public record DeadLetter(
            String inbox,
            String messageId,
            String type,
            String routingKey,
            long deaths,
            String reason,
            Instant deadLetteredAt,
            int bodyBytes) {}

    public record QueueDepth(String inbox, String deadLetterQueue, long messages) {}

    private final ConnectionFactory connectionFactory;
    private final List<String> inboxes;
    private final int scanLimit;

    public DeadLetters(ConnectionFactory connectionFactory, List<String> inboxes, int scanLimit) {
        this.connectionFactory = connectionFactory;
        this.inboxes = List.copyOf(inboxes);
        this.scanLimit = scanLimit;
    }

    public List<String> inboxes() {
        return inboxes;
    }

    public List<QueueDepth> depths() {
        return inboxes.stream()
                .map(inbox -> new QueueDepth(inbox, InboxQueues.deadLetterQueue(inbox), count(inbox)))
                .toList();
    }

    public long count(String inbox) {
        return withChannel(channel -> (long)
                channel.queueDeclarePassive(InboxQueues.deadLetterQueue(inbox)).getMessageCount());
    }

    /** Up to {@code limit} messages over all DLQs, left in place. */
    public List<DeadLetter> list(int limit) {
        List<DeadLetter> found = new ArrayList<>();
        for (String inbox : inboxes) {
            scan(inbox, Math.min(limit - found.size(), scanLimit), response -> {
                found.add(describe(inbox, response));
                return false;
            });
        }
        return found;
    }

    /** Puts the message back into its inbox. False when no DLQ holds it. */
    public boolean replay(String messageId) {
        for (String inbox : inboxes) {
            if (actOn(inbox, matching(messageId), true) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Every dead-lettered message back to its inbox; returns how many. */
    public int replayAll() {
        int replayed = 0;
        for (String inbox : inboxes) {
            replayed += actOn(inbox, response -> true, true);
        }
        return replayed;
    }

    /** Drops the message for good. False when no DLQ holds it. */
    public boolean discard(String messageId) {
        for (String inbox : inboxes) {
            if (actOn(inbox, matching(messageId), false) > 0) {
                return true;
            }
        }
        return false;
    }

    private static Predicate<GetResponse> matching(String messageId) {
        return response -> Objects.equals(messageId, response.getProps().getMessageId());
    }

    /** Replays (or drops) every message {@code take} selects; returns how many. */
    private int actOn(String inbox, Predicate<GetResponse> take, boolean replay) {
        int[] done = {0};
        scan(
                inbox,
                scanLimit,
                response -> {
                    boolean hit = take.test(response);
                    done[0] += hit ? 1 : 0;
                    return hit;
                },
                replay ? inbox : null);
        return done[0];
    }

    private void scan(String inbox, int limit, Predicate<GetResponse> take) {
        scan(inbox, limit, take, null);
    }

    /**
     * Reads up to {@code limit} messages from the inbox's DLQ. A message {@code take} accepts is republished to
     * {@code replayTo} (when not null) and acked; all others are nacked back.
     */
    private void scan(String inbox, int limit, Predicate<GetResponse> take, String replayTo) {
        withChannel(channel -> {
            List<Long> untouched = new ArrayList<>();
            try {
                if (replayTo != null) {
                    channel.confirmSelect();
                }
                GetResponse response;
                for (int i = 0;
                        i < limit && (response = channel.basicGet(InboxQueues.deadLetterQueue(inbox), false)) != null;
                        i++) {
                    long tag = response.getEnvelope().getDeliveryTag();
                    if (!take.test(response)) {
                        untouched.add(tag);
                        continue;
                    }
                    if (replayTo != null) {
                        channel.basicPublish(
                                "", replayTo, withoutDeathHeaders(response.getProps()), response.getBody());
                        channel.waitForConfirmsOrDie(5_000);
                    }
                    channel.basicAck(tag, false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the broker", e);
            } finally {
                for (long tag : untouched) {
                    channel.basicNack(tag, false, true);
                }
                // nacks get no reply; a synchronous call on the same channel returns only after they're processed
                channel.queueDeclarePassive(InboxQueues.deadLetterQueue(inbox));
            }
            return null;
        });
    }

    private static AMQP.BasicProperties withoutDeathHeaders(AMQP.BasicProperties props) {
        Map<String, Object> headers = new HashMap<>(props.getHeaders() == null ? Map.of() : props.getHeaders());
        headers.keySet()
                .removeIf(key ->
                        key.startsWith("x-death") || key.startsWith("x-first-death") || key.startsWith("x-last-death"));
        return props.builder().headers(headers).build();
    }

    @SuppressWarnings("unchecked")
    private static DeadLetter describe(String inbox, GetResponse response) {
        AMQP.BasicProperties props = response.getProps();
        Map<String, Object> headers = props.getHeaders() == null ? Map.of() : props.getHeaders();
        Map<String, Object> death = headers.get("x-death") instanceof List<?> list && !list.isEmpty()
                ? (Map<String, Object>) list.getFirst()
                : Map.of();
        String routingKey = death.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()
                ? String.valueOf(keys.getFirst())
                : response.getEnvelope().getRoutingKey();
        return new DeadLetter(
                inbox,
                props.getMessageId(),
                props.getType(),
                routingKey,
                death.get("count") instanceof Number n ? n.longValue() : 1,
                death.get("reason") == null ? null : death.get("reason").toString(),
                death.get("time") instanceof Date time ? time.toInstant() : null,
                response.getBody().length);
    }

    @FunctionalInterface
    private interface ChannelWork<T> {
        T run(Channel channel) throws IOException, TimeoutException;
    }

    private <T> T withChannel(ChannelWork<T> work) {
        // A channel of its own (not the template's cached one), so confirm mode and our unacked gets stay here.
        try (Connection connection = connectionFactory.createConnection();
                Channel channel = connection.createChannel(false)) {
            return work.run(channel);
        } catch (IOException | TimeoutException e) {
            throw new AmqpException("Dead-letter queue access failed: " + e.getMessage(), e);
        }
    }
}
