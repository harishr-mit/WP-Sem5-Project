package com.mitpass.notification.service;

import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationWaitlisted;
import com.mitpass.common.messaging.contract.WaitlistPromoted;
import com.mitpass.notification.client.EventDirectory;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Recipient;
import com.mitpass.notification.client.PassMedia;
import com.mitpass.notification.config.NotificationProperties;
import com.mitpass.notification.service.Mailer.Attachment;
import com.mitpass.notification.service.Mailer.Email;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Which email each domain event becomes (docs/plans/phase-7.md §6). Message emails are keyed by
 * {@code <messageId>:<recipient>}, reminders by {@code reminder:<offset>:<registrationId>}, so a redelivery or a second
 * job run never sends twice.
 */
@Service
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final Mailer mailer;
    private final EventDirectory events;
    private final PassMedia passes;
    private final IcsBuilder ics;
    private final NotificationProperties properties;

    public Notifications(
            Mailer mailer, EventDirectory events, PassMedia passes, IcsBuilder ics, NotificationProperties properties) {
        this.mailer = mailer;
        this.events = events;
        this.passes = passes;
        this.ics = ics;
        this.properties = properties;
    }

    public void passIssued(UUID messageId, PassIssued pass) {
        Optional<EventInfo> event = events.event(pass.eventId());
        Map<String, Object> model = base(pass.holderName(), event, pass.eventTitle(), pass.eventStartsAt(), null);
        model.put("passNumber", pass.passNumber());
        model.put("passUrl", properties.portalUrl() + "/my/passes/" + pass.passId());
        byte[] qr = passes.staticQrPng(pass.passId()).orElse(null);
        mailer.send(new Email(
                messageId + ":" + pass.holderEmail(),
                "pass-issued",
                pass.eventId(),
                pass.registrationId(),
                pass.holderEmail(),
                "Your pass for " + title(event, pass.eventTitle()),
                "pass-issued",
                model,
                qr,
                invite(event, false).orElse(null)));
    }

    public void waitlisted(UUID messageId, RegistrationWaitlisted waitlisted) {
        Optional<EventInfo> event = events.event(waitlisted.eventId());
        Map<String, Object> model = base(waitlisted.userName(), event, waitlisted.eventTitle(), null, null);
        model.put("position", waitlisted.position());
        mailer.send(new Email(
                messageId + ":" + waitlisted.userEmail(),
                "waitlisted",
                waitlisted.eventId(),
                waitlisted.registrationId(),
                waitlisted.userEmail(),
                "You're on the waitlist (#" + waitlisted.position() + ") for " + title(event, waitlisted.eventTitle()),
                "waitlisted",
                model,
                null,
                null));
    }

    public void promoted(UUID messageId, WaitlistPromoted promoted) {
        Optional<EventInfo> event = events.event(promoted.eventId());
        mailer.send(new Email(
                messageId + ":" + promoted.userEmail(),
                "promoted",
                promoted.eventId(),
                promoted.registrationId(),
                promoted.userEmail(),
                "You got a seat at " + title(event, promoted.eventCode()),
                "promoted",
                base(null, event, promoted.eventCode(), null, null),
                null,
                null));
    }

    public void registrationCancelled(UUID messageId, RegistrationCancelled cancelled) {
        if (cancelled.userEmail() == null) {
            log.warn(
                    "Registration {} cancelled, but the message has no email (sent before P7)",
                    cancelled.registrationId());
            return;
        }
        Optional<EventInfo> event = events.event(cancelled.eventId());
        mailer.send(new Email(
                messageId + ":" + cancelled.userEmail(),
                "registration-cancelled",
                cancelled.eventId(),
                cancelled.registrationId(),
                cancelled.userEmail(),
                "Registration cancelled: " + title(event, cancelled.eventCode()),
                "registration-cancelled",
                base(cancelled.userName(), event, cancelled.eventCode(), null, null),
                null,
                null));
    }

    /** Only a cancellation is news to students; every other move is visible in the portal. */
    public void eventStateChanged(UUID messageId, EventStateChanged changed) {
        events.forget(changed.eventId());
        if (!"CANCELLED".equals(changed.toStatus())) {
            return;
        }
        Optional<EventInfo> event = events.event(changed.eventId());
        Optional<Attachment> invite = invite(event, true);
        for (String status : List.of("CONFIRMED", "WAITLISTED")) {
            for (Recipient r : events.registrations(changed.eventId(), status)) {
                mailer.send(new Email(
                        messageId + ":" + r.userEmail(),
                        "event-cancelled",
                        changed.eventId(),
                        r.id(),
                        r.userEmail(),
                        title(event, changed.eventCode()) + " is cancelled",
                        "event-cancelled",
                        base(r.userName(), event, changed.eventCode(), changed.startsAt(), changed.endsAt()),
                        null,
                        invite.orElse(null)));
            }
        }
    }

    public void rescheduled(UUID messageId, EventRescheduled rescheduled) {
        events.forget(rescheduled.eventId());
        Optional<EventInfo> event = events.event(rescheduled.eventId());
        // the message's times are the change itself; the catalogue may already show a later one
        Optional<EventInfo> moved = event.map(e -> new EventInfo(
                e.id(),
                e.code(),
                e.title(),
                rescheduled.startsAt(),
                rescheduled.endsAt(),
                e.status(),
                e.version(),
                e.venue()));
        Optional<Attachment> invite = invite(moved, false);
        for (Recipient r : events.registrations(rescheduled.eventId(), "CONFIRMED")) {
            mailer.send(new Email(
                    messageId + ":" + r.userEmail(),
                    "event-rescheduled",
                    rescheduled.eventId(),
                    r.id(),
                    r.userEmail(),
                    title(event, rescheduled.eventCode()) + " has a new time",
                    "event-rescheduled",
                    base(r.userName(), moved, rescheduled.eventCode(), rescheduled.startsAt(), rescheduled.endsAt()),
                    null,
                    invite.orElse(null)));
        }
    }

    /** One reminder; {@code offset} is how long before the start it goes out (24 h, 1 h). */
    public boolean remind(EventInfo event, Duration offset, Recipient recipient) {
        Map<String, Object> model = base(recipient.userName(), Optional.of(event), event.title(), null, null);
        String in = inWords(offset);
        model.put("startsIn", in);
        model.put("passesUrl", properties.portalUrl() + "/my/passes");
        return mailer.send(new Email(
                "reminder:" + offset + ":" + recipient.id(),
                "reminder",
                event.id(),
                recipient.id(),
                recipient.userEmail(),
                event.title() + " starts " + in,
                "reminder",
                model,
                null,
                null));
    }

    static String inWords(Duration offset) {
        long hours = offset.toHours();
        if (hours >= 1) {
            return "in " + hours + (hours == 1 ? " hour" : " hours");
        }
        return "in " + offset.toMinutes() + " minutes";
    }

    private Map<String, Object> base(
            String name, Optional<EventInfo> event, String fallbackTitle, Instant startsAt, Instant endsAt) {
        Map<String, Object> model = new HashMap<>();
        model.put("name", name);
        model.put("eventTitle", title(event, fallbackTitle));
        // the message's times only when event-service doesn't know the event (a reschedule passes the moved event)
        Instant start = event.map(EventInfo::startsAt).orElse(startsAt);
        Instant end = event.map(EventInfo::endsAt).orElse(endsAt);
        model.put("when", when(start, end));
        model.put("place", event.map(EventInfo::place).orElse(null));
        model.put(
                "eventUrl",
                event.map(e -> properties.portalUrl() + "/events/" + e.id()).orElse(null));
        return model;
    }

    private String when(Instant start, Instant end) {
        if (start == null) {
            return null;
        }
        ZonedDateTime from = start.atZone(properties.timeZone());
        if (end == null) {
            return DAY_TIME.format(from);
        }
        ZonedDateTime to = end.atZone(properties.timeZone());
        return DAY_TIME.format(from) + " – "
                + (from.toLocalDate().equals(to.toLocalDate()) ? TIME.format(to) : DAY_TIME.format(to));
    }

    private Optional<Attachment> invite(Optional<EventInfo> event, boolean cancelled) {
        return event.filter(e -> e.startsAt() != null && e.endsAt() != null)
                .map(e -> new Attachment(
                        IcsBuilder.fileName(e),
                        IcsBuilder.CONTENT_TYPE,
                        ics.build(e, properties.portalUrl() + "/events/" + e.id(), cancelled)
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private static String title(Optional<EventInfo> event, String fallback) {
        return event.map(EventInfo::title).orElse(fallback);
    }
}
