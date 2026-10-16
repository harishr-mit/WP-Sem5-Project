package com.mitpass.pass.web;

import com.mitpass.pass.config.PassProperties;
import com.mitpass.pass.service.PassMediaService;
import com.mitpass.pass.service.PassMediaService.Format;
import com.mitpass.pass.service.PassMediaService.QrResult;
import com.mitpass.pass.web.dto.PassDtos.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR codes and the PDF ticket, for the pass holder only (D12). One path per code serves PNG (default), SVG or JSON by
 * {@code Accept} or {@code ?format=}. The mappings don't declare {@code produces}, so an error is still answered as
 * {@code application/problem+json} when the client asked for an image (plan §18 gotcha 14).
 */
@RestController
@RequestMapping("/api/passes/{id}")
@Tag(name = "Pass codes", description = "Live and static QR codes and the PDF ticket (holder only)")
public class PassMediaController {

    static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");

    private final PassMediaService media;
    private final PassProperties properties;

    public PassMediaController(PassMediaService media, PassProperties properties) {
        this.media = media;
        this.properties = properties;
    }

    @GetMapping("/qr/live")
    @PreAuthorize("@passSecurity.isHolder(#id)")
    @Operation(
            summary = "Live QR (rotating, valid 45 s)",
            description = "PNG by default; `image/svg+xml` or `application/json` (token + expiry) by Accept or "
                    + "`?format=svg|json`. Fetch a new one every 30 s. 409 `code-locked` (with `unlocksAt`) before "
                    + "the code unlocks 2 h before doors open; 409 `pass-not-valid` for a revoked or expired pass.")
    public ResponseEntity<?> live(
            @PathVariable UUID id,
            @Parameter(description = "png, svg or json; overrides Accept") @RequestParam(required = false)
                    String format,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        Format wanted = format(format, accept);
        return respond(
                media.live(id, wanted), wanted, properties.liveRefreshInterval().toSeconds());
    }

    @GetMapping("/qr/static")
    @PreAuthorize("@passSecurity.isHolder(#id)")
    @Operation(
            summary = "Static QR (valid until the event ends + 2 h)",
            description = "The code printed on the PDF ticket. 409 `static-qr-disabled` if the event allows only "
                    + "the live code.")
    public ResponseEntity<?> staticCode(
            @PathVariable UUID id,
            @RequestParam(required = false) String format,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        Format wanted = format(format, accept);
        return respond(media.staticCode(id, wanted), wanted, null);
    }

    @GetMapping("/ticket.pdf")
    @PreAuthorize("@passSecurity.isHolder(#id)")
    @Operation(summary = "PDF ticket (A6) with the static QR, if the event allows it")
    public ResponseEntity<byte[]> ticket(@PathVariable UUID id) {
        byte[] pdf = media.ticket(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, TicketFileName.header(media.ticketFileName(id)))
                .body(pdf);
    }

    private static ResponseEntity<?> respond(QrResult result, Format format, Long refreshAfterSeconds) {
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        return switch (format) {
            case JSON ->
                ok.contentType(MediaType.APPLICATION_JSON)
                        .body(new TokenResponse(
                                result.token().value(),
                                result.token().kind().name(),
                                result.token().issuedAt(),
                                result.token().expiresAt(),
                                refreshAfterSeconds));
            case SVG -> ok.contentType(SVG).body(result.image());
            case PNG -> ok.contentType(MediaType.IMAGE_PNG).body(result.image());
        };
    }

    /** {@code ?format=} wins; else the first of PNG / SVG / JSON the Accept header names by quality; else PNG. */
    static Format format(String format, String accept) {
        if (format != null && !format.isBlank()) {
            return switch (format.toLowerCase(Locale.ROOT)) {
                case "svg" -> Format.SVG;
                case "json" -> Format.JSON;
                default -> Format.PNG;
            };
        }
        if (accept == null || accept.isBlank()) {
            return Format.PNG;
        }
        List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        } catch (IllegalArgumentException ex) {
            return Format.PNG;
        }
        types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed()); // stable: ties keep their order
        for (MediaType type : types) {
            if (type.equalsTypeAndSubtype(MediaType.IMAGE_PNG)) {
                return Format.PNG;
            }
            if (type.equalsTypeAndSubtype(SVG)) {
                return Format.SVG;
            }
            if (type.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)) {
                return Format.JSON;
            }
        }
        return Format.PNG;
    }
}
