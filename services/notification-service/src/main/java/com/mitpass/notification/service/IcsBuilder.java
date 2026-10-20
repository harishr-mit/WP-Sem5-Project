package com.mitpass.notification.service;

import com.mitpass.notification.client.EventDirectory.EventInfo;
import java.net.URI;
import java.time.Clock;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Url;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;
import org.springframework.stereotype.Component;

/**
 * Calendar invites (RFC 5545) for an event (D16): one stable {@code UID} per event, so a calendar app updates the entry
 * it already has; {@code SEQUENCE} is the event's version, which grows with every edit. Times are UTC.
 */
@Component
public class IcsBuilder {

    public static final String CONTENT_TYPE = "text/calendar; charset=UTF-8";

    private final Clock clock;

    public IcsBuilder(Clock clock) {
        this.clock = clock;
    }

    /** {@code cancelled}: METHOD:CANCEL and STATUS:CANCELLED, which removes the entry in most calendar apps. */
    public String build(EventInfo event, String eventUrl, boolean cancelled) {
        VEvent entry = new VEvent(event.startsAt(), event.endsAt(), event.title());
        entry.add(new Uid("event-" + event.id() + "@mitpass"));
        entry.add(new DtStamp(clock.instant()));
        entry.add(new Sequence(event.version() == null ? 0 : event.version().intValue()));
        if (event.place() != null) {
            entry.add(new Location(event.place()));
        }
        if (eventUrl != null) {
            entry.add(new Url(URI.create(eventUrl)));
            entry.add(new Description("Your MITPass pass: " + eventUrl));
        }
        if (cancelled) {
            entry.add(ImmutableStatus.VEVENT_CANCELLED);
        }

        Calendar calendar = new Calendar();
        calendar.add(new ProdId("-//MITPass//Event invites//EN"));
        calendar.add(ImmutableVersion.VERSION_2_0);
        calendar.add(ImmutableCalScale.GREGORIAN);
        calendar.add(cancelled ? ImmutableMethod.CANCEL : ImmutableMethod.PUBLISH);
        calendar.add(entry);
        return calendar.toString();
    }

    public static String fileName(EventInfo event) {
        return (event.code() == null ? "event-" + event.id() : event.code()) + ".ics";
    }
}
