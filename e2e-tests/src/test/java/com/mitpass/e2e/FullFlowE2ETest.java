package com.mitpass.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The whole product through the gateway of a running stack (docker-compose.yml): a new student signs up and verifies by
 * email (Mailpit), registers for an event, gets a pass, an admin starts the event now, a volunteer scans the live code
 * (ACCEPTED, then ALREADY_CHECKED_IN), the organizer's attendance shows 1, and the scan is one trace in Zipkin.
 * Addresses come from {@code MITPASS_GATEWAY}, {@code MITPASS_MAILPIT}, {@code MITPASS_ZIPKIN} (defaults: localhost).
 * Uses the fixed demo accounts (D8) for organizer, volunteer and admin. 127.0.0.1, not localhost: on Windows the IPv6
 * attempt against Docker's port forward can stall.
 */
class FullFlowE2ETest {

    static final String GATEWAY = System.getenv().getOrDefault("MITPASS_GATEWAY", "http://127.0.0.1:8080");
    static final String MAILPIT = System.getenv().getOrDefault("MITPASS_MAILPIT", "http://127.0.0.1:8025");
    static final String ZIPKIN = System.getenv().getOrDefault("MITPASS_ZIPKIN", "http://127.0.0.1:9411");

    final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void aStudentSignsUpRegistersGetsAPassAndIsScannedIn() throws Exception {
        String regNo = "2099" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        String email = regNo + "@student.annauniv.edu";

        // 1. sign up, verify through the email, log in
        Response signup = send(
                "POST",
                "/api/auth/signup",
                null,
                Map.of(
                        "email",
                        email,
                        "password",
                        "Techfest2026!",
                        "fullName",
                        "E2E Student",
                        "department",
                        "CSE",
                        "year",
                        2));
        assertThat(signup.status()).as(signup.body()).isEqualTo(201);
        String token = await("verification mail", () -> verificationToken(email));
        assertThat(send("POST", "/api/auth/verify-email", null, Map.of("token", token))
                        .status())
                .isEqualTo(200);
        String student = login(email, "Techfest2026!");

        // 2. an event for tomorrow, opened for registration by its organizer, with a gate
        String organizer = login("organizer.a@mitpass.test", "Passw0rd!");
        String code = "E2E" + regNo.substring(4);
        Response created = send(
                "POST",
                "/api/events",
                organizer,
                Map.of(
                        "code",
                        code,
                        "title",
                        "E2E " + code,
                        "categoryId",
                        1,
                        "venueId",
                        1,
                        "capacity",
                        10,
                        "startsAt",
                        Instant.now().plus(Duration.ofMinutes(25)).toString(),
                        "endsAt",
                        Instant.now().plus(Duration.ofHours(4)).toString(),
                        "registrationClosesAt",
                        Instant.now().plus(Duration.ofMinutes(20)).toString()));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        int eventId = created.json("$.id");
        for (String to : List.of("PUBLISHED", "REGISTRATION_OPEN")) {
            assertThat(send("POST", "/api/events/" + eventId + "/transitions", organizer, Map.of("to", to))
                            .status())
                    .isEqualTo(200);
        }
        Response gate =
                send("POST", "/api/events/" + eventId + "/gates", organizer, Map.of("code", "MAIN", "name", "Main"));
        int gateId = gate.json("$.id");

        // 3. the student registers and a pass appears
        Response registration = send("POST", "/api/registrations", student, Map.of("eventId", eventId));
        assertThat(registration.status()).as(registration.body()).isEqualTo(201);
        assertThat((String) registration.json("$.status")).isEqualTo("CONFIRMED");
        String passId = await("the pass", () -> {
            Response mine = send("GET", "/api/passes/me", student, null);
            List<String> ids = mine.json("$.content[?(@.eventId == " + eventId + ")].id");
            return ids.isEmpty() ? null : ids.get(0);
        });

        // 4. the gate opens (an admin starts the event now), the volunteer scans the live code
        assertThat(send(
                                "POST",
                                "/api/events/" + eventId + "/transitions",
                                organizer,
                                Map.of("to", "REGISTRATION_CLOSED"))
                        .status())
                .isEqualTo(200);
        String admin = login("admin@mitpass.test", "Passw0rd!");
        assertThat(send("POST", "/api/events/" + eventId + "/transitions", admin, Map.of("to", "LIVE", "reason", "e2e"))
                        .status())
                .isEqualTo(200);
        String volunteer = login("volunteer@mitpass.test", "Passw0rd!");
        assertThat(send(
                                "POST",
                                "/api/events/" + eventId + "/volunteers",
                                organizer,
                                Map.of("email", "volunteer@mitpass.test", "gateId", gateId))
                        .status())
                .isEqualTo(201);

        String live = await("the live code", () -> {
            Response r = send("GET", "/api/passes/" + passId + "/qr/live", student, null);
            return r.status() == 200 ? r.json("$.token") : null;
        });
        Response scan = await("an accepted scan", () -> {
            Response r =
                    send("POST", "/api/scans", volunteer, Map.of("token", live, "gateId", gateId, "deviceId", "e2e"));
            return "ACCEPTED".equals(r.json("$.result"))
                    ? r
                    : null; // checkin's copy of the event/pass may lag a moment
        });
        assertThat((String) scan.json("$.color")).isEqualTo("GREEN");
        Response again =
                send("POST", "/api/scans", volunteer, Map.of("token", live, "gateId", gateId, "deviceId", "e2e"));
        assertThat(again.status()).isEqualTo(409);
        assertThat((String) again.json("$.result")).isEqualTo("ALREADY_CHECKED_IN");

        // 5. the organizer sees one attendee; a pass email reached the student
        await("attendance", () -> {
            Response r = send("GET", "/api/attendance/events/" + eventId + "/summary", organizer, null);
            return (Integer) r.json("$.checkedIn") == 1 ? r : null;
        });
        assertThat(mailBody(email, "pass")).as("the pass email").isNotNull();

        // 6. the scan is a single trace across services in Zipkin
        List<String> services = await("the scan trace", this::scanTraceServices);
        assertThat(services).contains("api-gateway", "checkin-service");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    record Response(int status, String body, java.net.http.HttpHeaders headers) {
        <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    Response send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(GATEWAY + path)).timeout(Duration.ofSeconds(30));
        request.header("Accept", "application/json"); // the QR endpoints answer a PNG otherwise
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json(body)));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers());
    }

    String login(String email, String password) throws Exception {
        Response r = send("POST", "/api/auth/login", null, Map.of("email", email, "password", password));
        assertThat(r.status()).as("login %s: %s", email, r.body()).isEqualTo(200);
        return r.json("$.accessToken");
    }

    String verificationToken(String email) throws Exception {
        String text = mailBody(email, "verify");
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** The newest Mailpit message to {@code email} whose text mentions {@code needle}; null if none yet. */
    String mailBody(String email, String needle) throws Exception {
        String query = URLEncoder.encode("to:" + email, StandardCharsets.UTF_8);
        HttpResponse<String> list = http.send(
                HttpRequest.newBuilder(URI.create(MAILPIT + "/api/v1/search?query=" + query))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (list.statusCode() != 200) {
            return null;
        }
        List<String> ids = JsonPath.read(list.body(), "$.messages[*].ID");
        for (String id : ids) {
            HttpResponse<String> message = http.send(
                    HttpRequest.newBuilder(URI.create(MAILPIT + "/api/v1/message/" + id))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String text = message.body();
            if (text.toLowerCase().contains(needle)) {
                return JsonPath.read(text, "$.Text") + " " + JsonPath.read(text, "$.HTML");
            }
        }
        return null;
    }

    /** The services of a recent scan trace that crossed more than one of them; null until Zipkin has it. */
    List<String> scanTraceServices() throws Exception {
        HttpResponse<String> traces = http.send(
                HttpRequest.newBuilder(URI.create(ZIPKIN + "/api/v2/traces?serviceName=checkin-service&limit=50"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (traces.statusCode() != 200) {
            return null;
        }
        List<List<Map<String, Object>>> all = JsonPath.read(traces.body(), "$");
        for (List<Map<String, Object>> trace : all) {
            boolean scan =
                    trace.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("/api/scans"));
            if (scan) {
                List<String> names = trace.stream()
                        .map(s -> (String) ((Map<?, ?>) s.get("localEndpoint")).get("serviceName"))
                        .distinct()
                        .toList();
                if (names.size() > 1) {
                    return names;
                }
            }
        }
        return null;
    }

    static String json(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.append(first ? "" : ",").append('"').append(e.getKey()).append("\":");
                append(out, e.getValue());
                first = false;
            }
            out.append('}');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            out.append('"')
                    .append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
    }

    /** Polls until the supplier returns something (up to 60 s); the stack is asynchronous (outbox, queues, copies). */
    static <T> T await(String what, ThrowingSupplier<T> supplier) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                T value = supplier.get();
                if (value != null) {
                    return value;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for " + what, last);
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
