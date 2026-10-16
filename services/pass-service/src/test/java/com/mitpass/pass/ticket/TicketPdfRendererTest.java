package com.mitpass.pass.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.pass.qr.QrRenderer;
import com.mitpass.pass.qr.QrRendererTest;
import com.mitpass.pass.ticket.TicketPdfRenderer.Ticket;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.core.io.DefaultResourceLoader;

/** The ticket is a one-page PDF with the event, holder, pass number and QR (or the live-code note). */
class TicketPdfRendererTest {

    final TicketPdfRenderer renderer = new TicketPdfRenderer(QrRendererTest.properties());
    final QrRenderer qr = new QrRenderer(QrRendererTest.properties(), new DefaultResourceLoader());

    @Test
    void ticketWithStaticQr() throws Exception {
        byte[] pdf = renderer.render(ticket(qr.png("token-for-test", "#1D4E89")));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        java.nio.file.Files.write(java.nio.file.Path.of("target", "ticket-sample.pdf"), pdf); // for an eye check
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text)
                    .contains("TechFest Hackathon")
                    .contains("TECHFEST26-000042-7")
                    .contains("Priya Raman")
                    .contains("PR") // initials badge
                    .contains("Main Auditorium")
                    .contains("Sat 14 Nov 2026");
            assertThat(text).doesNotContain("accepts only the live code");
        } finally {
            reader.close();
        }
    }

    @Test
    void ticketWithoutStaticQrSaysToUseTheLiveCode() throws Exception {
        PdfReader reader = new PdfReader(renderer.render(ticket(null)));
        try {
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("accepts only the live code");
        } finally {
            reader.close();
        }
    }

    @Test
    void initials() {
        assertThat(TicketPdfRenderer.initials("Priya Raman")).isEqualTo("PR");
        assertThat(TicketPdfRenderer.initials("arun kumar s")).isEqualTo("AS");
        assertThat(TicketPdfRenderer.initials("Madonna")).isEqualTo("MA");
        assertThat(TicketPdfRenderer.initials("  ")).isEqualTo("?");
    }

    private static Ticket ticket(byte[] qrPng) {
        Instant start = Instant.parse("2026-11-14T03:30:00Z");
        return new Ticket(
                UUID.randomUUID(),
                "TECHFEST26-000042-7",
                "ISSUED",
                "TECHFEST26",
                "TechFest Hackathon",
                start,
                start.plusSeconds(8 * 3600),
                "Main Auditorium",
                "Admin Block",
                "https://maps.example/main",
                "Priya Raman",
                "#1D4E89",
                qrPng);
    }
}
