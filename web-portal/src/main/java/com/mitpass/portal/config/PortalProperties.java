package com.mitpass.portal.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.portal.*}.
 *
 * @param gatewayUrl base URL of the API gateway; every backend call goes there
 * @param mailpitUrl dev mail inbox shown after sign-up; blank hides the link
 * @param qrRefresh how often the pass page swaps the live QR image (live tokens last 45 s)
 * @param refreshAhead refresh the access token this long before it expires (docs/plans/phase-6.md §7)
 * @param searchDelay debounce of the catalogue's live search
 * @param pollInterval how often the "You're in" page and waitlisted registrations ask again
 * @param pollTimeout after this the "You're in" page stops asking and points to My passes
 * @param healthRefresh how often the admin health page refreshes itself
 * @param waitlistRefresh how often a waitlisted registration asks whether it moved up
 * @param scanRepeatIgnore the scanner ignores the same QR text for this long (one code stays in view for many frames)
 * @param supportedLocales languages the switch offers; the first is the default
 */
@ConfigurationProperties("mitpass.portal")
public record PortalProperties(
        @DefaultValue("http://localhost:8080") String gatewayUrl,
        @DefaultValue("") String mailpitUrl,
        @DefaultValue Timeouts timeouts,
        @DefaultValue("30s") Duration qrRefresh,
        @DefaultValue("60s") Duration refreshAhead,
        @DefaultValue("300ms") Duration searchDelay,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("30s") Duration pollTimeout,
        @DefaultValue("10s") Duration healthRefresh,
        @DefaultValue("15s") Duration waitlistRefresh,
        @DefaultValue("3s") Duration scanRepeatIgnore,
        @DefaultValue({"en", "ta"}) List<String> supportedLocales) {

    public record Timeouts(
            @DefaultValue("2s") Duration connect,
            @DefaultValue("10s") Duration read) {}
}
