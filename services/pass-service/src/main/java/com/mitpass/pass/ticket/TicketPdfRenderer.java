package com.mitpass.pass.ticket;

import com.mitpass.pass.config.PassProperties;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openpdf.text.Anchor;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * The PDF ticket (PROJECT.md §P4): an A6 page with a coloured header (event title), date and venue with a map link, the
 * holder's name next to an initials badge, the pass number in monospace, and the static QR, or a note that only the
 * live code works. Built-in fonts (Latin-1); P8 embeds a Unicode font if needed.
 */
@Component
public class TicketPdfRenderer {

    /** Everything printed on the ticket. {@code qrPng} is null when the event allows only the live code. */
    public record Ticket(
            UUID passId,
            String passNumber,
            String status,
            String eventCode,
            String eventTitle,
            Instant startsAt,
            Instant endsAt,
            String venueName,
            String venueBuilding,
            String mapUrl,
            String holderName,
            String color,
            byte[] qrPng) {}

    private static final Color INK = new Color(0x22, 0x22, 0x22);
    private static final Color MUTED = new Color(0x66, 0x66, 0x66);

    private final ZoneId zone;
    private final DateTimeFormatter dayFormat;
    private final DateTimeFormatter timeFormat;

    public TicketPdfRenderer(PassProperties properties) {
        this.zone = properties.timeZone();
        this.dayFormat =
                DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH).withZone(zone);
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone);
    }

    public byte[] render(Ticket ticket) {
        Color brand = Color.decode(ticket.color());
        Document document = new Document(PageSize.A6, 18, 18, 18, 18);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.addTitle("MITPass ticket " + ticket.passNumber());
            document.addAuthor("MITPass");
            document.open();

            document.add(header(ticket, brand));
            document.add(spacer(6));
            document.add(line(
                    "When",
                    dayFormat.format(ticket.startsAt()) + ", "
                            + timeFormat.format(ticket.startsAt()) + " to " + timeFormat.format(ticket.endsAt())
                            + " (" + zone.getId() + ")"));
            document.add(venue(ticket, brand));
            document.add(spacer(6));
            document.add(holder(writer, ticket, brand));
            document.add(spacer(4));

            if (ticket.qrPng() != null) {
                Image qr = Image.getInstance(ticket.qrPng());
                qr.scaleToFit(150, 150);
                qr.setAlignment(Element.ALIGN_CENTER);
                document.add(qr);
            } else {
                Paragraph note = new Paragraph(
                        "This event accepts only the live code. Open the pass in My passes at the gate.",
                        font(Font.BOLDITALIC, 9, brand));
                note.setAlignment(Element.ALIGN_CENTER);
                note.setSpacingBefore(20);
                note.setSpacingAfter(20);
                document.add(note);
            }

            Paragraph number = new Paragraph(ticket.passNumber(), new Font(Font.COURIER, 13, Font.BOLD, INK));
            number.setAlignment(Element.ALIGN_CENTER);
            document.add(number);
            Paragraph small = new Paragraph(
                    "Pass " + ticket.passId() + " | " + ticket.status() + " | verify at /.well-known/pass-jwks.json",
                    font(Font.NORMAL, 5.5f, MUTED));
            small.setAlignment(Element.ALIGN_CENTER);
            document.add(small);
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not build the ticket PDF", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    /** "Priya Raman" → "PR"; one name → its first two letters. */
    static String initials(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "?";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return Arrays.stream(new String[] {parts[0], parts[parts.length - 1]})
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
    }

    private PdfPTable header(Ticket ticket, Color brand) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(brand);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);
        Paragraph brandLine = new Paragraph("MITPASS  |  " + ticket.eventCode(), font(Font.BOLD, 7, Color.WHITE));
        Paragraph title = new Paragraph(ticket.eventTitle(), font(Font.BOLD, 13, Color.WHITE));
        title.setSpacingBefore(2);
        cell.addElement(brandLine);
        cell.addElement(title);
        table.addCell(cell);
        return table;
    }

    private Paragraph venue(Ticket ticket, Color brand) {
        String where = ticket.venueName() == null
                ? "Venue to be announced"
                : ticket.venueName() + (ticket.venueBuilding() == null ? "" : ", " + ticket.venueBuilding());
        Paragraph paragraph = line("Where", where);
        if (ticket.mapUrl() != null && !ticket.mapUrl().isBlank()) {
            paragraph.add(new Chunk("  "));
            Anchor map = new Anchor("Open map", font(Font.UNDERLINE, 8, brand));
            map.setReference(ticket.mapUrl());
            paragraph.add(map);
        }
        return paragraph;
    }

    private PdfPTable holder(PdfWriter writer, Ticket ticket, Color brand) throws IOException {
        PdfPTable table = new PdfPTable(new float[] {1, 4});
        table.setWidthPercentage(100);
        PdfPCell badge = new PdfPCell();
        badge.setBorder(Rectangle.NO_BORDER);
        badge.setFixedHeight(34);
        badge.setCellEvent((cell, position, canvases) -> {
            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            float cx = position.getLeft() + 17;
            float cy = (position.getTop() + position.getBottom()) / 2;
            canvas.saveState();
            canvas.setColorFill(brand);
            canvas.circle(cx, cy, 15);
            canvas.fill();
            canvas.restoreState();
            try {
                BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, false);
                PdfContentByte text = canvases[PdfPTable.TEXTCANVAS];
                text.beginText();
                text.setColorFill(Color.WHITE);
                text.setFontAndSize(bold, 11);
                text.showTextAligned(Element.ALIGN_CENTER, initials(ticket.holderName()), cx, cy - 4, 0);
                text.endText();
            } catch (DocumentException | IOException ex) {
                throw new IllegalStateException("Could not draw the initials badge", ex);
            }
        });
        table.addCell(badge);
        PdfPCell name = new PdfPCell();
        name.setBorder(Rectangle.NO_BORDER);
        name.setVerticalAlignment(Element.ALIGN_MIDDLE);
        name.addElement(new Paragraph("HOLDER", font(Font.BOLD, 6, MUTED)));
        name.addElement(new Paragraph(ticket.holderName(), font(Font.BOLD, 11, INK)));
        table.addCell(name);
        return table;
    }

    private static Paragraph line(String label, String value) {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase(label.toUpperCase(Locale.ROOT) + "  ", font(Font.BOLD, 6.5f, MUTED)));
        paragraph.add(new Phrase(value, font(Font.NORMAL, 8.5f, INK)));
        paragraph.setSpacingAfter(2);
        return paragraph;
    }

    private static Paragraph spacer(float height) {
        Paragraph paragraph = new Paragraph(" ", font(Font.NORMAL, 2, Color.WHITE));
        paragraph.setSpacingAfter(height);
        return paragraph;
    }

    private static Font font(int style, float size, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }
}
