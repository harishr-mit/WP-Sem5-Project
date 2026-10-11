package com.mitpass.auth.mail;

import com.mitpass.auth.config.AuthProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

/**
 * Sends the verification email (to Mailpit in dev). After commit, so a rolled-back sign-up sends nothing; async, so a
 * slow or broken SMTP server doesn't delay or fail the request. Phase 7 moves emails to notification-service.
 */
@Component
public class VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailer.class);

    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    public VerificationMailer(JavaMailSender mailSender, AuthProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(VerificationRequested request) {
        String link =
                properties.verifyLink().replace("{token}", URLEncoder.encode(request.token(), StandardCharsets.UTF_8));
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mailFrom());
            helper.setTo(request.email());
            helper.setSubject("Verify your MITPass account");
            helper.setText(plainText(request.fullName(), link), html(request.fullName(), link));
            mailSender.send(message);
            log.info("Verification email sent to {}", request.email());
        } catch (MessagingException | MailException ex) {
            log.warn("Could not send the verification email to {}: {}", request.email(), ex.getMessage());
        }
    }

    private String plainText(String name, String link) {
        return """
                Hi %s,

                Welcome to MITPass. Open this link to verify your email address:

                %s

                The link works once and expires in %d hours. If you didn't sign up, ignore this email.
                """.formatted(name, link, properties.verificationTokenTtl().toHours());
    }

    private String html(String name, String link) {
        String safeLink = HtmlUtils.htmlEscape(link);
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:auto;color:#1d2433">
                  <div style="background:#6b1d2a;color:#fff;padding:16px 20px;font-weight:bold;letter-spacing:2px">
                    MITPASS</div>
                  <div style="padding:20px;background:#eef1f6">
                    <p>Hi %s,</p>
                    <p>Welcome to MITPass. Verify your email address to finish creating your account.</p>
                    <p><a href="%s" style="display:inline-block;background:#6b1d2a;color:#fff;padding:10px 18px;
                       text-decoration:none;border-radius:4px">Verify my email</a></p>
                    <p style="font-size:12px;color:#556">The link works once and expires in %d hours.
                       If you didn't sign up, ignore this email.</p>
                  </div>
                </div>
                """.formatted(
                        HtmlUtils.htmlEscape(name),
                        safeLink,
                        properties.verificationTokenTtl().toHours());
    }
}
