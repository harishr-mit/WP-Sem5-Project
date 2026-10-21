package com.mitpass.notification;

import com.icegreen.greenmail.util.GreenMail;
import com.mitpass.common.messaging.contract.EventType;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Recipient;
import com.mitpass.notification.client.EventDirectory.Venue;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/** Events, recipients, AMQP messages and MIME helpers. Ids and addresses are unique per JVM: the context is shared. */
public final class Fixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong IDS = new AtomicLong(9000);

    private Fixtures() {}

    public static EventInfo event(Instant startsAt) {
        long id = IDS.incrementAndGet();
        return new EventInfo(
                id,
                "EVT" + id,
                "Event " + id,
                startsAt,
                startsAt.plus(Duration.ofHours(3)),
                "REGISTRATION_OPEN",
                2L,
                new Venue("Main Auditorium", "Block A", null));
    }

    public static Recipient recipient(String status) {
        UUID user = UUID.randomUUID();
        return new Recipient(
                UUID.randomUUID(), user, "Student " + user.toString().substring(0, 4), email(), status);
    }

    public static String email() {
        return UUID.randomUUID() + "@student.test";
    }

    public static Message message(Object payload, UUID messageId) {
        EventType type = payload.getClass().getAnnotation(EventType.class);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", messageId);
        envelope.put("type", type.value());
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", TestClock.START);
        envelope.put("aggregateType", "Test");
        envelope.put("aggregateId", "1");
        envelope.put("correlationId", null);
        envelope.put("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(type.value());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(JSON.writeValueAsBytes(envelope), properties);
    }

    public static Message message(Object payload) {
        return message(payload, UUID.randomUUID());
    }

    /** The messages GreenMail received for one address (the server is shared by every test). */
    public static List<MimeMessage> mailTo(GreenMail greenMail, String address) {
        return Arrays.stream(greenMail.getReceivedMessages())
                .filter(m -> {
                    try {
                        Address[] to = m.getAllRecipients();
                        return to != null
                                && Arrays.stream(to).anyMatch(a -> a.toString().equals(address));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    /** Every leaf part of a message, depth first. */
    public static List<Part> parts(MimeMessage message) throws Exception {
        List<Part> found = new ArrayList<>();
        collect(message, found);
        return found;
    }

    private static void collect(Part part, List<Part> found) throws Exception {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, found);
            }
        } else {
            found.add(part);
        }
    }

    public static String html(MimeMessage message) throws Exception {
        for (Part part : parts(message)) {
            if (part.isMimeType("text/html")) {
                return (String) part.getContent();
            }
        }
        throw new AssertionError("no HTML part");
    }
}
