package com.mitpass.security.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records a role × endpoint authorization matrix and prints it as Markdown (docs/plans/phase-2.md exit criterion 4).
 *
 * <p>Each cell is one real request. Only a <em>security</em> rejection counts as denied: 401 with problem type
 * {@code unauthorized} or 403 with {@code forbidden}. Any other status (200, 201, 204, 400, 404, 422, or a business 401
 * like {@code invalid-credentials}) means the request got past authorization, which is what "allowed" means here.
 */
public final class SecurityMatrix {

    public static final String ANONYMOUS = "anonymous";

    public enum Verdict {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN
    }

    /** Who may call an endpoint. */
    public record Rule(boolean isPublic, boolean anyAuthenticated, Set<String> callers) {

        public static Rule publicEndpoint() {
            return new Rule(true, false, Set.of());
        }

        public static Rule authenticated() {
            return new Rule(false, true, Set.of());
        }

        public static Rule only(String... callers) {
            return new Rule(false, false, Collections.unmodifiableSet(new LinkedHashSet<>(List.of(callers))));
        }

        public Verdict expectedFor(String caller) {
            if (isPublic) {
                return Verdict.ALLOWED;
            }
            if (ANONYMOUS.equals(caller)) {
                return Verdict.UNAUTHORIZED;
            }
            return anyAuthenticated || callers.contains(caller) ? Verdict.ALLOWED : Verdict.FORBIDDEN;
        }

        String describe() {
            if (isPublic) {
                return "public";
            }
            return anyAuthenticated ? "any logged-in user" : String.join(", ", callers);
        }
    }

    /** A 5xx never passes: it hides whether security let the call through (found in P5, checkin-service). */
    private record Cell(Verdict expected, Verdict actual, int status) {

        boolean passed() {
            return expected == actual && status < 500;
        }
    }

    private final String title;
    private final List<String> callers;
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final Map<String, Map<String, Cell>> cells = new LinkedHashMap<>();

    public SecurityMatrix(String title, List<String> callers) {
        this.title = title;
        this.callers = List.copyOf(callers);
    }

    public List<String> callers() {
        return callers;
    }

    public static Verdict verdictOf(int status, String body) {
        String text = body == null ? "" : body;
        if (status == 401 && text.contains("urn:mitpass:problem:unauthorized")) {
            return Verdict.UNAUTHORIZED;
        }
        if (status == 403 && text.contains("urn:mitpass:problem:forbidden")) {
            return Verdict.FORBIDDEN;
        }
        return Verdict.ALLOWED;
    }

    public void record(String endpoint, Rule rule, String caller, int status, String body) {
        rules.put(endpoint, rule);
        cells.computeIfAbsent(endpoint, e -> new LinkedHashMap<>())
                .put(caller, new Cell(rule.expectedFor(caller), verdictOf(status, body), status));
    }

    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        cells.forEach((endpoint, row) -> row.forEach((caller, cell) -> {
            if (!cell.passed()) {
                failures.add("%s as %s: expected %s, got %s (HTTP %d%s)"
                        .formatted(
                                endpoint,
                                caller,
                                cell.expected(),
                                cell.actual(),
                                cell.status(),
                                cell.status() >= 500 ? ", a server error" : ""));
            }
        }));
        return failures;
    }

    public int size() {
        return cells.values().stream().mapToInt(Map::size).sum();
    }

    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("### ").append(title).append("\n\n");
        md.append("| Endpoint | Rule | ").append(String.join(" | ", callers)).append(" |\n");
        md.append("|---|---|").append("---|".repeat(callers.size())).append('\n');
        cells.forEach((endpoint, row) -> {
            md.append("| `")
                    .append(endpoint)
                    .append("` | ")
                    .append(rules.get(endpoint).describe())
                    .append(" |");
            for (String caller : callers) {
                Cell cell = row.get(caller);
                md.append(' ').append(cell == null ? "–" : render(cell)).append(" |");
            }
            md.append('\n');
        });
        long passed = cells.values().stream()
                .flatMap(row -> row.values().stream())
                .filter(Cell::passed)
                .count();
        md.append("\n").append(passed).append(" / ").append(size()).append(" cells as expected.\n");
        return md.toString();
    }

    public void writeTo(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toMarkdown(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String render(Cell cell) {
        String mark = cell.passed() ? "✅" : "❌";
        String what = switch (cell.actual()) {
            case ALLOWED -> String.valueOf(cell.status());
            case UNAUTHORIZED -> "401";
            case FORBIDDEN -> "403";
        };
        return mark + " " + what;
    }
}
