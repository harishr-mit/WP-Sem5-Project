package com.mitpass.pass.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * The PDF ticket's file name (REVIEW-LATER RL5, owner 2026-10-16): searchable by students, unique for us. Event title,
 * holder name and pass number joined by underscores, each part reduced to letters, digits and single dashes:
 * {@code TechFest-Hackathon_Priya-Raman_TECHFEST26-000042-5.pdf}. Sent as an ASCII {@code filename} plus the UTF-8
 * {@code filename*} (RFC 6266), so names with accents or other scripts survive in browsers that support it.
 */
public final class TicketFileName {

    private static final int MAX_PART = 60;

    private TicketFileName() {}

    public static String of(String eventTitle, String holderName, String passNumber) {
        List<String> parts = new ArrayList<>();
        for (String raw : new String[] {eventTitle, holderName, passNumber}) {
            String part = clean(raw);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return String.join("_", parts) + ".pdf";
    }

    /** Accents removed, anything else outside ASCII letters and digits turned into dashes. */
    static String ascii(String fileName) {
        String stripped = Normalizer.normalize(fileName, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /** {@code attachment; filename="<ascii>"; filename*=UTF-8''<percent-encoded>}. */
    public static String header(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii(fileName) + "\"; filename*=UTF-8''" + encoded;
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String part = raw.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (part.length() > MAX_PART) {
            part = part.substring(0, MAX_PART).replaceAll("-+$", "");
        }
        return part;
    }
}
