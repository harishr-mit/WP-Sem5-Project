package com.mitpass.notification.service;

import static com.mitpass.notification.Fixtures.email;
import static com.mitpass.notification.Fixtures.mailTo;
import static com.mitpass.notification.Fixtures.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.icegreen.greenmail.util.GreenMail;
import com.mitpass.common.messaging.contract.RegistrationWaitlisted;
import com.mitpass.notification.FakeEventDirectory;
import com.mitpass.notification.Fixtures;
import com.mitpass.notification.NotificationIntegrationTest;
import com.mitpass.notification.TestClock;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.messaging.NotificationInboxListener;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * SMTP down: the handler throws, nothing is marked as sent or processed, so the container's retries (and later a replay
 * from the DLQ) send the email once SMTP is back.
 */
@NotificationIntegrationTest
class SmtpFailureTest {

    @MockitoSpyBean
    JavaMailSender mail;

    @Autowired
    NotificationInboxListener listener;

    @Autowired
    FakeEventDirectory events;

    @Autowired
    GreenMail greenMail;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aFailedSendLeavesNoTraceAndARetrySendsIt() {
        EventInfo event = Fixtures.event(TestClock.START.plus(Duration.ofDays(5)));
        events.put(event);
        String to = email();
        UUID messageId = UUID.randomUUID();
        Message waitlisted = message(
                new RegistrationWaitlisted(
                        UUID.randomUUID(),
                        event.id(),
                        event.code(),
                        event.title(),
                        UUID.randomUUID(),
                        "Arun",
                        to,
                        1,
                        TestClock.START),
                messageId);

        doThrow(new MailSendException("SMTP down")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> listener.onMessage(waitlisted)).isInstanceOf(MailSendException.class);
        assertThat(count("notification_log", "dedup_key = '" + messageId + ":" + to + "'"))
                .isZero();
        assertThat(count("processed_message", "message_id = '" + messageId + "'"))
                .isZero();

        reset(mail);
        listener.onMessage(waitlisted);
        assertThat(mailTo(greenMail, to)).hasSize(1);
    }

    private long count(String table, String where) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Long.class)
                .single();
    }
}
