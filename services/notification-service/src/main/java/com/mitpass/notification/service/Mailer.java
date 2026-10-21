package com.mitpass.notification.service;

import com.mitpass.notification.config.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders and sends one email, at most once per {@code dedupKey} (D16). Each email runs in its own short transaction
 * that claims its {@code notification_log} row first: a bulk mail that fails half-way is retried by the listener, and
 * the recipients already sent are skipped. An SMTP failure rolls the claim back and is rethrown (retry, then the DLQ).
 * A crash between the send and the commit can send one duplicate (accepted, owner Q10).
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private static final String CLAIM = """
            INSERT INTO notification_log (dedup_key, kind, event_id, registration_id, recipient, subject, sent_at)
            VALUES (:key, :kind, :eventId, :registrationId, :recipient, :subject, :sentAt)
            ON CONFLICT (dedup_key) DO NOTHING
            """;

    public record Attachment(String fileName, String contentType, byte[] content) {}

    /**
     * @param template a Thymeleaf template under {@code templates/mail/}
     * @param qrPng shown inline as {@code cid:qr}, or null
     * @param attachment a calendar invite, or null
     */
    public record Email(
            String dedupKey,
            String kind,
            Long eventId,
            UUID registrationId,
            String to,
            String subject,
            String template,
            Map<String, Object> model,
            byte[] qrPng,
            Attachment attachment) {}

    private final JavaMailSender mail;
    private final ITemplateEngine templates;
    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private final NotificationProperties properties;
    private final Clock clock;

    public Mailer(
            JavaMailSender mail,
            ITemplateEngine templates,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            NotificationProperties properties,
            Clock clock) {
        this.mail = mail;
        this.templates = templates;
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
    }

    /** @return true if sent now, false if this key was sent before */
    public boolean send(Email email) {
        return Boolean.TRUE.equals(ownTransaction.execute(status -> {
            int claimed = jdbc.sql(CLAIM)
                    .param("key", email.dedupKey())
                    .param("kind", email.kind())
                    .param("eventId", email.eventId())
                    .param("registrationId", email.registrationId())
                    .param("recipient", email.to())
                    .param("subject", email.subject())
                    .param("sentAt", Timestamp.from(clock.instant()))
                    .update();
            if (claimed == 0) {
                log.info("Skipping {} email {}: already sent", email.kind(), email.dedupKey());
                return false;
            }
            mail.send(render(email));
            log.info("Sent {} email to {}", email.kind(), email.to());
            return true;
        }));
    }

    private MimeMessage render(Email email) {
        Map<String, Object> model = new HashMap<>(email.model());
        model.put("subject", email.subject());
        model.put("portalUrl", properties.portalUrl());
        model.put("hasQr", email.qrPng() != null);
        String html = templates.process("mail/" + email.template(), new Context(Locale.ENGLISH, model));
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(html, true);
            if (email.qrPng() != null) {
                helper.addInline("qr", new ByteArrayResource(email.qrPng()), "image/png");
            }
            if (email.attachment() != null) {
                helper.addAttachment(
                        email.attachment().fileName(),
                        new ByteArrayResource(email.attachment().content()),
                        email.attachment().contentType());
            }
            return message;
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }
}
