package com.mitpass.auth.service;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.config.AuthProperties.DomainRule;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Who may sign up (docs/plans/phase-2.md §4 "Email policy"): only the configured domains. For
 * {@code student.annauniv.edu} the part before {@code @} must be a register number; for Gmail, dots and {@code +tags}
 * are ignored when deciding whether an account exists.
 */
@Component
public class EmailPolicy {

    /**
     * Outcome of checking one address.
     *
     * @param registerNumber taken from the address for domains that encode it, else {@code null}
     * @param error why the address is not allowed, or {@code null} if it is
     */
    public record Evaluation(String email, String canonicalEmail, String registerNumber, String error) {

        public boolean allowed() {
            return error == null;
        }
    }

    private record CompiledRule(DomainRule rule, Pattern localPart) {}

    private final Map<String, CompiledRule> rules = new LinkedHashMap<>();
    private final String allowedList;

    public EmailPolicy(AuthProperties properties) {
        for (DomainRule rule : properties.signup().domains()) {
            Pattern pattern = rule.localPartPattern() == null ? null : Pattern.compile(rule.localPartPattern());
            rules.put(rule.domain().toLowerCase(Locale.ROOT), new CompiledRule(rule, pattern));
        }
        this.allowedList = rules.keySet().stream().map(domain -> "@" + domain).collect(Collectors.joining(" or "));
    }

    public Evaluation evaluate(String rawEmail) {
        String email = normalize(rawEmail);
        int at = email.lastIndexOf('@');
        if (at < 1 || at == email.length() - 1) {
            // Malformed: @Email on the request reports it; don't add a second message.
            return new Evaluation(email, email, null, null);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        CompiledRule compiled = rules.get(domain);
        if (compiled == null) {
            return new Evaluation(email, email, null, "must be an address ending in " + allowedList);
        }
        DomainRule rule = compiled.rule();
        if (compiled.localPart() != null && !compiled.localPart().matcher(local).matches()) {
            String error = rule.registerNumberFromLocalPart()
                    ? "must be your register number @" + domain + " (10 or 12 digits, e.g. 2026509032@" + domain + ")"
                    : "is not a valid @" + domain + " address";
            return new Evaluation(email, email, null, error);
        }
        String registerNumber = rule.registerNumberFromLocalPart() ? local : null;
        return new Evaluation(email, canonical(local, domain, rule), registerNumber, null);
    }

    /** The lookup key for an address (login, resend, lookup), without checking whether sign-up would allow it. */
    public String canonicalize(String rawEmail) {
        String email = normalize(rawEmail);
        int at = email.lastIndexOf('@');
        if (at < 1) {
            return email;
        }
        String domain = email.substring(at + 1);
        CompiledRule compiled = rules.get(domain);
        return compiled == null ? email : canonical(email.substring(0, at), domain, compiled.rule());
    }

    private static String canonical(String local, String domain, DomainRule rule) {
        if (!rule.canonicalizeGmail()) {
            return local + "@" + domain;
        }
        int plus = local.indexOf('+');
        String base = plus >= 0 ? local.substring(0, plus) : local;
        return base.replace(".", "") + "@" + domain;
    }

    private static String normalize(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
