package com.mitpass.load;

import com.mitpass.seed.SeedPeople;
import com.mitpass.seed.SeedPeople.Person;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Before the load: the opening night of the bulk seed goes LIVE (its organizer closes registration, an admin starts it
 * now: the RL10 override), and the forty seeded volunteers log in, ten per gate. Plain HTTP, once, in the simulation's
 * constructor. Safe to run twice (an event already LIVE stays LIVE).
 */
final class ArrivalSetup {

    static final String GATEWAY = System.getenv().getOrDefault("MITPASS_GATEWAY", "http://127.0.0.1:8080");
    static final String FLAGSHIP = "FEST26OPEN";

    /** {@code volunteerTokens.get(g)} are the tokens of the volunteers at {@code gateIds.get(g)}. */
    record Opened(int eventId, List<Integer> gateIds, List<List<String>> volunteerTokens) {}

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private ArrivalSetup() {}

    static Opened openTheDoors(int arrivals) {
        try {
            String organizer = login("organizer.a@mitpass.test", "9.9.9.1");
            String admin = login("admin@mitpass.test", "9.9.9.2");
            int eventId = firstInt(get("/api/events?q=" + FLAGSHIP, organizer), "\"id\":(\\d+)");
            if (!get("/api/events/" + eventId, organizer).contains("\"status\":\"LIVE\"")) {
                post("/api/events/" + eventId + "/transitions", organizer, "{\"to\":\"REGISTRATION_CLOSED\"}");
                post("/api/events/" + eventId + "/transitions", admin, "{\"to\":\"LIVE\",\"reason\":\"load test\"}");
            }
            List<Integer> gateIds = new ArrayList<>();
            Matcher gates = Pattern.compile("\"id\":(\\d+),\"code\":\"(MAIN|NORTH|SOUTH|EAST)\"")
                    .matcher(get("/api/events/" + eventId + "/gates", organizer));
            while (gates.find()) {
                gateIds.add(Integer.parseInt(gates.group(1)));
            }
            java.util.Collections.sort(gateIds); // created MAIN, NORTH, SOUTH, EAST: volunteer i is at gate i % 4
            if (gateIds.size() != 4) {
                throw new IllegalStateException("expected the seed's 4 gates, found " + gateIds);
            }
            List<List<String>> tokens = new ArrayList<>();
            gateIds.forEach(g -> tokens.add(new ArrayList<>()));
            List<Person> crew = SeedPeople.volunteers(40); // event-service assigns volunteer i to gate i % 4
            for (int i = 0; i < crew.size(); i++) {
                tokens.get(i % gateIds.size())
                        .add(login(crew.get(i).email(), "9.8.%d.%d".formatted(i / 250, i % 250 + 1)));
            }
            System.out.printf(
                    "Load test ready: event %d is LIVE, gates %s, %d volunteers%n", eventId, gateIds, crew.size());
            return new Opened(eventId, gateIds, tokens);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "The stack isn't ready for the load test (docker compose up --build, seed done?)", e);
        }
    }

    private static String login(String email, String ip) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(GATEWAY + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", ip)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + SeedPeople.PASSWORD + "\"}"))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("login " + email + " -> " + response.statusCode() + " " + response.body());
        }
        return firstString(response.body(), "\"accessToken\":\"([^\"]+)\"");
    }

    private static String get(String path, String token) throws Exception {
        return HTTP.send(
                        HttpRequest.newBuilder(URI.create(GATEWAY + path))
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static void post(String path, String token, String body) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(GATEWAY + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(path + " " + body + " -> " + r.statusCode() + " " + r.body());
        }
    }

    private static int firstInt(String text, String regex) {
        return Integer.parseInt(firstString(text, regex));
    }

    private static String firstString(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("no match for " + regex + " in " + text);
        }
        return m.group(1);
    }
}
