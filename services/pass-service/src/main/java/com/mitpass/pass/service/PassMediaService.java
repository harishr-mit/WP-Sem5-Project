package com.mitpass.pass.service;

import com.mitpass.pass.client.EventCatalogClient;
import com.mitpass.pass.client.EventCatalogClient.EventInfo;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.qr.QrRenderer;
import com.mitpass.pass.ticket.TicketPdfRenderer;
import com.mitpass.pass.ticket.TicketPdfRenderer.Ticket;
import com.mitpass.pass.token.PassTokenService;
import com.mitpass.pass.token.PassTokenService.PassToken;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tokens, QR images and the PDF ticket for a pass holder (docs/plans/phase-4.md §7). The live code needs an ACTIVE pass
 * (409 {@code code-locked} before it unlocks); the static code needs a valid pass on an event that allows it.
 */
@Service
@Transactional
public class PassMediaService {

    /** Format of a QR answer. */
    public enum Format {
        PNG,
        SVG,
        JSON
    }

    private final PassLifecycleService lifecycle;
    private final PassTokenService tokens;
    private final EventCatalogClient catalog;
    private final QrRenderer qr;
    private final TicketPdfRenderer tickets;
    private final PassPolicy policy;
    private final Clock clock;

    public PassMediaService(
            PassLifecycleService lifecycle,
            PassTokenService tokens,
            EventCatalogClient catalog,
            QrRenderer qr,
            TicketPdfRenderer tickets,
            PassPolicy policy,
            Clock clock) {
        this.lifecycle = lifecycle;
        this.tokens = tokens;
        this.catalog = catalog;
        this.qr = qr;
        this.tickets = tickets;
        this.policy = policy;
        this.clock = clock;
    }

    /** A token and, unless JSON was asked for, its QR image in the event's colour. */
    public record QrResult(PassToken token, byte[] image) {}

    /** The live code (45 s). 409 {@code code-locked} before it unlocks, {@code pass-not-valid} after the end. */
    public QrResult live(UUID passId, Format format) {
        Pass pass = lifecycle.activateIfDue(passId);
        if (pass.getStatus() == PassStatus.ISSUED) {
            throw PassProblems.codeLocked(passId, policy.unlocksAt(pass.getEvent()));
        }
        if (pass.getStatus() != PassStatus.ACTIVE) {
            throw PassProblems.passNotValid(passId, pass.getStatus());
        }
        return render(pass, tokens.live(pass), format);
    }

    /** The static code (until the event ends + grace). 409 {@code static-qr-disabled} if the event forbids it. */
    public QrResult staticCode(UUID passId, Format format) {
        Pass pass = lifecycle.activateIfDue(passId);
        requireValid(pass);
        if (!catalog.get(pass.getEvent().getId()).allowsStaticQr()) {
            throw PassProblems.staticQrDisabled(pass.getEvent().getId());
        }
        return render(pass, tokens.staticToken(pass, policy.staticTokenExpiry(pass.getEvent())), format);
    }

    private QrResult render(Pass pass, PassToken token, Format format) {
        if (format == Format.JSON) {
            return new QrResult(token, null);
        }
        String color = catalog.get(pass.getEvent().getId()).passColor();
        return new QrResult(token, format == Format.SVG ? qr.svg(token.value(), color) : qr.png(token.value(), color));
    }

    public byte[] ticket(UUID passId) {
        Pass pass = lifecycle.activateIfDue(passId);
        requireValid(pass);
        EventInfo event = catalog.get(pass.getEvent().getId());
        String color = qr.colorOrDefault(event.passColor());
        byte[] qrPng = null;
        if (event.allowsStaticQr()) {
            PassToken token = tokens.staticToken(pass, policy.staticTokenExpiry(pass.getEvent()));
            qrPng = qr.png(token.value(), color);
        }
        EventInfo.Venue venue = event.venue();
        return tickets.render(new Ticket(
                pass.getId(),
                pass.getPassNumber(),
                pass.getStatus().name(),
                pass.getEvent().getCode(),
                pass.getEvent().getTitle(),
                pass.getEvent().getStartsAt(),
                pass.getEvent().getEndsAt(),
                venue == null ? null : venue.name(),
                venue == null ? null : venue.building(),
                venue == null ? null : venue.mapUrl(),
                pass.getHolderName(),
                color,
                qrPng));
    }

    public String passNumber(UUID passId) {
        return lifecycle.activateIfDue(passId).getPassNumber();
    }

    private void requireValid(Pass pass) {
        Instant now = clock.instant();
        if (!pass.getStatus().isValid() || !now.isBefore(policy.staticTokenExpiry(pass.getEvent()))) {
            throw PassProblems.passNotValid(pass.getId(), pass.getStatus());
        }
    }
}
