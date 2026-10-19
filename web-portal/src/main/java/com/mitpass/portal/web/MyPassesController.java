package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.ResourceAccessException;

/**
 * The wallet (UI reference screen 4). The QR image and the PDF are fetched from pass-service server side and passed
 * through as bytes, so the token stays inside the image and the browser never holds an API token (D9 BFF rule, kept
 * in P6). The pass card swaps the live code every {@code mitpass.portal.qr-refresh} with a countdown ring
 * ({@code pass-card.js}; D9 row "plain timer" replaced); the card shows the SVG, crisp at any size (RL6 cause b).
 */
@Controller
public class MyPassesController {

    /** Valid passes first (soonest event first), then the rest (newest first). */
    static final Comparator<Pass> WALLET_ORDER = Comparator.comparing((Pass p) -> !p.isValid())
            .thenComparing(
                    (Pass p) -> p.isValid()
                            ? p.startsAt()
                            : Instant.MAX.minusSeconds(p.startsAt().getEpochSecond()),
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private final GatewayClient gateway;
    private final PortalProperties properties;

    public MyPassesController(GatewayClient gateway, PortalProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @GetMapping("/my/passes")
    public String list(Model model) {
        List<Pass> passes =
                gateway.myPasses().content().stream().sorted(WALLET_ORDER).toList();
        model.addAttribute("passes", passes);
        return "my/passes";
    }

    @GetMapping("/my/passes/{id}")
    public String pass(@PathVariable UUID id, Model model) {
        Pass pass = gateway.pass(id);
        model.addAttribute("pass", pass);
        model.addAttribute("event", eventOf(pass));
        model.addAttribute("refreshSeconds", properties.qrRefresh().toSeconds());
        model.addAttribute("refreshMillis", properties.qrRefresh().toMillis());
        return "my/pass";
    }

    /** The venue and the event's colour for the card; the pass page still works if event-service doesn't answer. */
    private EventDetail eventOf(Pass pass) {
        try {
            return pass.eventId() == null ? null : gateway.event(pass.eventId());
        } catch (ApiProblemException | ResourceAccessException ex) {
            return null;
        }
    }

    /** The live QR as PNG (a fresh 45 s token each time). A 409 (locked, revoked) is passed on without a body. */
    @GetMapping("/my/passes/{id}/qr.png")
    public ResponseEntity<byte[]> liveQr(@PathVariable UUID id) {
        try {
            return image(gateway.liveQrPng(id).body(), MediaType.IMAGE_PNG);
        } catch (ApiProblemException ex) {
            return noCode(ex);
        }
    }

    /** The same as SVG, which the pass card uses. Served with a CSP that forbids scripts if opened directly. */
    @GetMapping("/my/passes/{id}/qr.svg")
    public ResponseEntity<byte[]> liveQrSvg(@PathVariable UUID id) {
        try {
            Binary svg = gateway.liveQrSvg(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("image/svg+xml"))
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                    .body(svg.body());
        } catch (ApiProblemException ex) {
            return noCode(ex);
        }
    }

    private static ResponseEntity<byte[]> image(byte[] body, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static ResponseEntity<byte[]> noCode(ApiProblemException ex) {
        return ResponseEntity.status(ex.getStatus() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/my/passes/{id}/ticket.pdf")
    public ResponseEntity<byte[]> ticket(@PathVariable UUID id) {
        Binary pdf = gateway.ticketPdf(id);
        ResponseEntity.BodyBuilder ok =
                ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore());
        if (pdf.contentDisposition() != null) {
            ok.header(HttpHeaders.CONTENT_DISPOSITION, pdf.contentDisposition());
        }
        return ok.body(pdf.body());
    }
}
