package com.mitpass.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import com.mitpass.seed.SeedPeople;
import com.mitpass.seed.SeedPeople.Person;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * "1,000 students arriving in 10 minutes across 4 gates" (PROJECT.md §P8), with a doors-open peak: 300 of them in the
 * first minute. Each virtual student logs in, finds their pass, gets the live QR code and is scanned in by one of the
 * four gates' volunteers; every step must succeed. Needs the stack with the bulk seed (the students are
 * {@link SeedPeople}). {@link ArrivalSetup} opens the event first.
 *
 * <p>Each student logs in from their own address ({@code X-Forwarded-For}), because the gateway limits logins per
 * address and every virtual student here shares the load generator's: the same header the portal sends for a browser.
 */
public class ArrivalSimulation extends Simulation {

    static final int ARRIVALS = env("ARRIVALS", 1000);
    static final int PEAK = env("PEAK", 300);
    static final int MINUTES = env("MINUTES", 10);
    /** Students 1..1,100 are registered for the opening night; a short smoke run can use the spare ones (OFFSET=1000). */
    static final int OFFSET = env("OFFSET", 0);

    private static int env(String name, int fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    final ArrivalSetup.Opened event = ArrivalSetup.openTheDoors(ARRIVALS);

    final List<Person> people = SeedPeople.students(OFFSET + ARRIVALS).subList(OFFSET, OFFSET + ARRIVALS);

    final Iterator<Map<String, Object>> students = Stream.iterate(0, i -> i + 1)
            .map(i -> student(people.get(i % ARRIVALS), i))
            .iterator();

    private Map<String, Object> student(Person p, int i) {
        int gate = i % event.gateIds().size();
        List<String> volunteers = event.volunteerTokens().get(gate);
        return Map.of(
                "email", p.email(),
                "ip", "10.%d.%d.%d".formatted(i / 65_536 % 256, i / 256 % 256, i % 256 + 1),
                "gateId", event.gateIds().get(gate),
                "volunteerToken", volunteers.get(i / event.gateIds().size() % volunteers.size()));
    }

    final HttpProtocolBuilder protocol =
            http.baseUrl(ArrivalSetup.GATEWAY).acceptHeader("application/json").contentTypeHeader("application/json");

    final ScenarioBuilder arrival = scenario("A student arrives at the gate")
            .feed(students)
            .exec(http("1 login")
                    .post("/api/auth/login")
                    .header("X-Forwarded-For", "#{ip}")
                    .body(StringBody("{\"email\":\"#{email}\",\"password\":\"" + SeedPeople.PASSWORD + "\"}"))
                    .check(status().is(200), jsonPath("$.accessToken").saveAs("token")))
            .exec(http("2 find my pass")
                    .get("/api/passes/me?size=100")
                    .header("Authorization", "Bearer #{token}")
                    .check(
                            status().is(200),
                            jsonPath("$.content[?(@.eventId == " + event.eventId() + ")].id")
                                    .saveAs("passId")))
            .exec(http("3 live QR code")
                    .get("/api/passes/#{passId}/qr/live")
                    .header("Authorization", "Bearer #{token}")
                    .check(status().is(200), jsonPath("$.token").saveAs("qr")))
            .exec(http("4 scan at the gate")
                    .post("/api/scans")
                    .header("Authorization", "Bearer #{volunteerToken}")
                    .body(StringBody("{\"token\":\"#{qr}\",\"gateId\":#{gateId},\"deviceId\":\"gatling\"}"))
                    .check(status().is(200), jsonPath("$.result").is("ACCEPTED")));

    {
        setUp(arrival.injectOpen(
                        rampUsers(PEAK).during(60), // doors open: 300 in the first minute
                        rampUsers(ARRIVALS - PEAK).during(MINUTES * 60 - 60)))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().count().is(0L),
                        global().responseTime().percentile(95).lt(1500));
    }
}
