package com.mitpass.pass.config;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.passes.*} (docs/plans/phase-4.md §12, docs/DECISIONS.md D12).
 *
 * @param issuer the {@code iss} claim of pass tokens
 * @param keySecret encrypts the private signing keys at rest
 * @param liveTokenTtl lifetime of a live (rotating) QR token
 * @param liveRefreshInterval how often a client should fetch a new live token
 * @param staticValidAfterEnd a static (PDF) token is valid until the event ends plus this
 * @param codeUnlockBeforeStart the live code unlocks (ISSUED → ACTIVE) this long before the event starts
 * @param expireAfterEnd valid passes expire this long after the event ends (if COMPLETED never arrives)
 * @param eventCacheTtl how long event details read from event-service are reused
 * @param timeZone campus time zone for tickets and messages
 */
@ConfigurationProperties("mitpass.passes")
public record PassProperties(
        @DefaultValue("mitpass-pass") String issuer,
        @DefaultValue("dev-only-change-me") String keySecret,
        @DefaultValue("45s") Duration liveTokenTtl,
        @DefaultValue("30s") Duration liveRefreshInterval,
        @DefaultValue("2h") Duration staticValidAfterEnd,
        @DefaultValue("150m") Duration codeUnlockBeforeStart,
        @DefaultValue("2h") Duration expireAfterEnd,
        @DefaultValue("60s") Duration eventCacheTtl,
        @DefaultValue("Asia/Kolkata") ZoneId timeZone,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Qr qr) {

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration interval) {}

    /**
     * @param size target image width in pixels (rounded down to whole pixels per module)
     * @param margin quiet zone in modules
     * @param logo a PNG resource for the centre badge; empty = a drawn "MIT" badge
     * @param logoRatio badge width as a share of the code width (it covers about its square of the area; level M
     *     recovers about 15 % damage)
     * @param defaultColor dark-module colour when the event has none
     * @param errorCorrection QR error-correction level. M since 2026-10-19 (D12): at H a live token made a 113-module
     *     code that phone cameras couldn't read off a screen
     * @param minContrast the event colour is used for the modules only if it contrasts with white at least this much
     *     (WCAG contrast ratio, 1–21)
     * @param darkColor the modules' colour when the event colour is too light
     */
    public record Qr(
            @DefaultValue("400") int size,
            @DefaultValue("2") int margin,
            @DefaultValue("") String logo,
            @DefaultValue("0.15") double logoRatio,
            @DefaultValue("#7A1F2B") String defaultColor,
            @DefaultValue("M") ErrorCorrectionLevel errorCorrection,
            @DefaultValue("7.0") double minContrast,
            @DefaultValue("#111111") String darkColor) {}
}
