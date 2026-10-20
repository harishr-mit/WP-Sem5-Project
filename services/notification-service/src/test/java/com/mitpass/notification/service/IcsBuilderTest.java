package com.mitpass.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Venue;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.Test;

class IcsBuilderTest {

    static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
    static final EventInfo EVENT = new EventInfo(
            42,
            "TECHFEST26",
            "TechFest Hackathon",
            Instant.parse("2026-11-24T04:30:00Z"),
            Instant.parse("2026-11-24T10:30:00Z"),
            "REGISTRATION_OPEN",
            3L,
            new Venue("Main Auditorium", "Block A", null));

    IcsBuilder builder = new IcsBuilder(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anInviteParsesWithAStableUidAndTheVersionAsSequence() throws Exception {
        String ics = builder.build(EVENT, "http://portal.test/events/42", false);

        Calendar calendar = new CalendarBuilder().build(new StringReader(ics));
        VEvent event = calendar.<VEvent>getComponent("VEVENT").orElseThrow();
        assertThat(calendar.getProperty("METHOD").orElseThrow().getValue()).isEqualTo("PUBLISH");
        assertThat(event.getProperty("UID").orElseThrow().getValue()).isEqualTo("event-42@mitpass");
        assertThat(event.getProperty("SEQUENCE").orElseThrow().getValue()).isEqualTo("3");
        assertThat(event.getProperty("SUMMARY").orElseThrow().getValue()).isEqualTo("TechFest Hackathon");
        assertThat(event.getProperty("LOCATION").orElseThrow().getValue()).isEqualTo("Main Auditorium, Block A");
        assertThat(ics).contains("DTSTART:20261124T043000Z").contains("DTEND:20261124T103000Z");
        assertThat(ics).doesNotContain("STATUS:CANCELLED");
    }

    @Test
    void aCancellationRemovesTheEntry() throws Exception {
        String ics = builder.build(EVENT, null, true);

        Calendar calendar = new CalendarBuilder().build(new StringReader(ics));
        assertThat(calendar.getProperty("METHOD").orElseThrow().getValue()).isEqualTo("CANCEL");
        assertThat(ics).contains("STATUS:CANCELLED").contains("UID:event-42@mitpass");
    }

    @Test
    void fileNameIsTheEventCode() {
        assertThat(IcsBuilder.fileName(EVENT)).isEqualTo("TECHFEST26.ics");
    }
}
