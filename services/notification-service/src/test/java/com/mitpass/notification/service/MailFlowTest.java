package com.mitpass.notification.service;

import static com.mitpass.notification.Fixtures.email;
import static com.mitpass.notification.Fixtures.html;
import static com.mitpass.notification.Fixtures.mailTo;
import static com.mitpass.notification.Fixtures.message;
import static com.mitpass.notification.Fixtures.parts;
import static com.mitpass.notification.Fixtures.recipient;
import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.PassIssued;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationWaitlisted;
import com.mitpass.common.messaging.contract.WaitlistPromoted;
import com.mitpass.notification.FakeEventDirectory;
import com.mitpass.notification.FakePassMedia;
import com.mitpass.notification.Fixtures;
import com.mitpass.notification.NotificationIntegrationTest;
import com.mitpass.notification.TestClock;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Recipient;
import com.mitpass.notification.messaging.NotificationInboxListener;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Each domain event becomes the right email, once (docs/plans/phase-7.md §6, D16). */
@NotificationIntegrationTest
class MailFlowTest {

    @Autowired
    NotificationInboxListener listener;

    @Autowired
    FakeEventDirectory events;

    @Autowired
    FakePassMedia passes;

    @Autowired
    GreenMail greenMail;

    @Autowired
    JdbcClient jdbc;

    @Test
    void passIssuedCarriesTheQrInlineAndACalendarInvite() throws Exception {
        EventInfo event = upcoming();
        String to = email();
        UUID pass = UUID.randomUUID();

        listener.onMessage(message(passIssued(event, pass, to)));

        List<MimeMessage> mails = mailTo(greenMail, to);
        assertThat(mails).hasSize(1);
        MimeMessage mail = mails.getFirst();
        assertThat(mail.getSubject()).isEqualTo("Your pass for " + event.title());
        assertThat(mail.getFrom()[0].toString()).contains("no-reply@mitpass.local");
        String html = html(mail);
        assertThat(html)
                .contains("Priya Raman")
                .contains("TECH-000001-3")
                .contains("cid:qr")
                .contains("http://portal.test/my/passes/" + pass)
                .contains("Tue 24 Nov 2026, 8:30 AM – 11:30 AM") // Asia/Kolkata
                .contains("Main Auditorium, Block A");

        Part qr = part(mail, "image/png");
        assertThat(((MimePart) qr).getContentID()).isEqualTo("<qr>");
        Part invite = part(mail, "text/calendar");
        assertThat(invite.getFileName()).isEqualTo(event.code() + ".ics");
        assertThat(text(invite))
                .contains("UID:event-" + event.id() + "@mitpass")
                .contains("METHOD:PUBLISH");
    }

    @Test
    void aRedeliveredMessageSendsOneEmail() {
        EventInfo event = upcoming();
        String to = email();
        Message message = message(passIssued(event, UUID.randomUUID(), to));

        listener.onMessage(message);
        listener.onMessage(message);

        assertThat(mailTo(greenMail, to)).hasSize(1);
    }

    @Test
    void liveOnlyEventsGetAWalletLinkInsteadOfTheCode() throws Exception {
        EventInfo event = upcoming();
        String to = email();
        UUID pass = UUID.randomUUID();
        passes.liveOnly(pass);

        listener.onMessage(message(passIssued(event, pass, to)));

        MimeMessage mail = mailTo(greenMail, to).getFirst();
        assertThat(parts(mail)).noneMatch(p -> isType(p, "image/png"));
        assertThat(html(mail)).contains("live code").doesNotContain("cid:qr");
    }

    @Test
    void waitlistPromotionAndCancellationEmails() throws Exception {
        EventInfo event = upcoming();
        String to = email();
        UUID registration = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Instant now = TestClock.START;

        listener.onMessage(message(new RegistrationWaitlisted(
                registration, event.id(), event.code(), event.title(), user, "Arun K", to, 3, now)));
        listener.onMessage(message(
                new WaitlistPromoted(registration, event.id(), event.code(), user, to, now, UUID.randomUUID())));
        listener.onMessage(message(new RegistrationCancelled(
                registration, event.id(), event.code(), user, "CONFIRMED", now, user.toString(), "Arun K", to)));

        List<MimeMessage> mails = mailTo(greenMail, to);
        assertThat(mails)
                .extracting(MimeMessage::getSubject)
                .containsExactly(
                        "You're on the waitlist (#3) for " + event.title(),
                        "You got a seat at " + event.title(),
                        "Registration cancelled: " + event.title());
        assertThat(html(mails.getFirst())).contains("Hi Arun K,").contains("#3");
    }

    @Test
    void aCancellationFromBeforeP7HasNoAddressAndIsSkipped() {
        EventInfo event = upcoming();
        listener.onMessage(message(new RegistrationCancelled(
                UUID.randomUUID(),
                event.id(),
                event.code(),
                UUID.randomUUID(),
                "CONFIRMED",
                TestClock.START,
                "x",
                null,
                null)));
        // nothing to assert on GreenMail by address; the handler just mustn't fail
        assertThat(sentFor(event)).isZero();
    }

    @Test
    void anEventCancellationEmailsEveryRegistrantOnceEvenWhenTheMessageRunsAgain() throws Exception {
        EventInfo event = upcoming();
        Recipient a = recipient("CONFIRMED");
        Recipient b = recipient("CONFIRMED");
        Recipient c = recipient("WAITLISTED");
        events.registrations(event.id(), "CONFIRMED", List.of(a, b));
        events.registrations(event.id(), "WAITLISTED", List.of(c));
        UUID messageId = UUID.randomUUID();
        Message cancelled = message(stateChanged(event, "CANCELLED"), messageId);

        listener.onMessage(cancelled);
        // as after a crash half-way: the message is processed again; the log keys stop the second round
        jdbc.sql("DELETE FROM processed_message WHERE message_id = ?")
                .param(messageId)
                .update();
        listener.onMessage(cancelled);

        for (Recipient r : List.of(a, b, c)) {
            List<MimeMessage> mails = mailTo(greenMail, r.userEmail());
            assertThat(mails).hasSize(1);
            assertThat(mails.getFirst().getSubject()).isEqualTo(event.title() + " is cancelled");
            assertThat(text(part(mails.getFirst(), "text/calendar")))
                    .contains("METHOD:CANCEL")
                    .contains("STATUS:CANCELLED");
        }
        assertThat(sentFor(event)).isEqualTo(3);
    }

    @Test
    void otherStateChangesSendNothing() {
        EventInfo event = upcoming();
        events.registrations(event.id(), "CONFIRMED", List.of(recipient("CONFIRMED")));

        listener.onMessage(message(stateChanged(event, "REGISTRATION_CLOSED")));

        assertThat(sentFor(event)).isZero();
    }

    @Test
    void aRescheduleSendsTheNewTimeAndAnUpdatedInvite() throws Exception {
        EventInfo event = upcoming();
        Recipient a = recipient("CONFIRMED");
        events.registrations(event.id(), "CONFIRMED", List.of(a));
        Instant newStart = event.startsAt().plus(Duration.ofDays(1));

        listener.onMessage(message(new EventRescheduled(
                event.id(), event.code(), newStart, newStart.plus(Duration.ofHours(2)), TestClock.START, "org")));

        MimeMessage mail = mailTo(greenMail, a.userEmail()).getFirst();
        assertThat(mail.getSubject()).isEqualTo(event.title() + " has a new time");
        assertThat(html(mail)).contains("Wed 25 Nov 2026, 8:30 AM – 10:30 AM");
        assertThat(text(part(mail, "text/calendar"))).contains("DTSTART:20261125T030000Z");
    }

    private EventInfo upcoming() {
        EventInfo event = Fixtures.event(TestClock.START.plus(Duration.ofDays(10)));
        events.put(event);
        return event;
    }

    private static PassIssued passIssued(EventInfo event, UUID pass, String to) {
        return new PassIssued(
                pass,
                "TECH-000001-3",
                UUID.randomUUID(),
                event.id(),
                event.code(),
                event.title(),
                UUID.randomUUID(),
                "Priya Raman",
                to,
                TestClock.START,
                event.startsAt());
    }

    private static EventStateChanged stateChanged(EventInfo event, String to) {
        return new EventStateChanged(
                event.id(),
                event.code(),
                "REGISTRATION_OPEN",
                to,
                TestClock.START,
                "org",
                "MANUAL",
                event.startsAt(),
                event.endsAt());
    }

    private long sentFor(EventInfo event) {
        return jdbc.sql("SELECT count(*) FROM notification_log WHERE event_id = ?")
                .param(event.id())
                .query(Long.class)
                .single();
    }

    private static Part part(MimeMessage mail, String type) throws Exception {
        return parts(mail).stream()
                .filter(p -> isType(p, type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " part"));
    }

    private static boolean isType(Part part, String type) {
        try {
            return part.isMimeType(type);
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(Part part) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part.getDataHandler().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
