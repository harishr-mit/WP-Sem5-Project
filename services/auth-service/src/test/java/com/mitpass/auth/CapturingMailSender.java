package com.mitpass.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Keeps sent messages in memory instead of talking SMTP, so tests can read the verification link. */
public class CapturingMailSender extends JavaMailSenderImpl {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
        sent.addAll(List.of(mimeMessages));
    }

    /** The verification token from the newest email to this address, if one has arrived. */
    public Optional<String> latestTokenFor(String email) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            MimeMessage message = sent.get(i);
            try {
                if (message.getAllRecipients()[0].toString().equalsIgnoreCase(email)) {
                    Matcher matcher = TOKEN.matcher(contentOf(message));
                    if (matcher.find()) {
                        return Optional.of(matcher.group(1));
                    }
                }
            } catch (MessagingException | IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return Optional.empty();
    }

    public long countTo(String email) {
        return sent.stream()
                .filter(message -> {
                    try {
                        return message.getAllRecipients()[0].toString().equalsIgnoreCase(email);
                    } catch (MessagingException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .count();
    }

    private static String contentOf(MimeMessage message) throws MessagingException, IOException {
        var out = new java.io.ByteArrayOutputStream();
        message.writeTo(out);
        // Quoted-printable may soft-wrap long lines ("=\r\n") and encode "=" as "=3D".
        return out.toString(java.nio.charset.StandardCharsets.UTF_8)
                .replace("=\r\n", "")
                .replace("=3D", "=");
    }
}
