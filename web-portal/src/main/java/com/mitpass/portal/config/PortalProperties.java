package com.mitpass.portal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.portal.*}.
 *
 * @param gatewayUrl base URL of the API gateway; every backend call goes there
 * @param mailpitUrl dev mail inbox shown after sign-up; blank hides the link
 * @param qrRefresh how often the pass page swaps the live QR image (live tokens last 45 s)
 */
@ConfigurationProperties("mitpass.portal")
public record PortalProperties(
        @DefaultValue("http://localhost:8080") String gatewayUrl,
        @DefaultValue("") String mailpitUrl,
        @DefaultValue Timeouts timeouts,
        @DefaultValue("30s") Duration qrRefresh) {

    public record Timeouts(
            @DefaultValue("2s") Duration connect,
            @DefaultValue("10s") Duration read) {}
}
