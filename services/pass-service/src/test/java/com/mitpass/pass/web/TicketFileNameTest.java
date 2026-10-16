package com.mitpass.pass.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RL5 (owner, 2026-10-16): the ticket's file name is searchable by students (event title and their name) and unique for
 * us (the pass number): {@code TechFest-Hackathon_Priya-Raman_TECHFEST26-000042-5.pdf}.
 */
class TicketFileNameTest {

    @Test
    void titleNameAndPassNumberJoinedWithUnderscores() {
        assertThat(TicketFileName.of("TechFest Hackathon", "Priya Raman", "TECHFEST26-000042-5"))
                .isEqualTo("TechFest-Hackathon_Priya-Raman_TECHFEST26-000042-5.pdf");
    }

    @Test
    void punctuationAndPathCharactersBecomeDashes() {
        assertThat(TicketFileName.of("AI/ML: Hands-on (Day 1)", "  O'Brien,  Sean ", "AIWS26-000001-0"))
                .isEqualTo("AI-ML-Hands-on-Day-1_O-Brien-Sean_AIWS26-000001-0.pdf");
        assertThat(TicketFileName.of("..\\..\\etc", "x\"y", "X26-000001-1")).doesNotContain("\\", "/", "\"", "..");
    }

    @Test
    void longPartsAreShortenedAndMissingPartsDropped() {
        String name = TicketFileName.of("A".repeat(200), null, "X26-000001-1");
        assertThat(name).isEqualTo("A".repeat(60) + "_X26-000001-1.pdf");
    }

    @Test
    void nonAsciiLettersKeptForFilenameStarAndTransliteratedForTheFallback() {
        String unicode = TicketFileName.of("Kalai Vizha", "Priyā Rāman", "KV26-000001-3");
        assertThat(unicode).isEqualTo("Kalai-Vizha_Priyā-Rāman_KV26-000001-3.pdf");
        assertThat(TicketFileName.ascii(unicode)).isEqualTo("Kalai-Vizha_Priya-Raman_KV26-000001-3.pdf");
        assertThat(TicketFileName.header(unicode))
                .isEqualTo("attachment; filename=\"Kalai-Vizha_Priya-Raman_KV26-000001-3.pdf\"; "
                        + "filename*=UTF-8''Kalai-Vizha_Priy%C4%81-R%C4%81man_KV26-000001-3.pdf");
    }
}
