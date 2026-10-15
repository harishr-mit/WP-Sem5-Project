package com.mitpass.event.web;

import static com.mitpass.security.test.SecurityMatrix.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.EventIntegrationTest.FakeAuthService;
import com.mitpass.event.TestData;
import com.mitpass.event.client.AuthServiceClient.UserAccount;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventGate;
import com.mitpass.event.domain.EventSession;
import com.mitpass.event.domain.Registration;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.domain.VolunteerAssignment;
import com.mitpass.event.repository.CategoryRepository;
import com.mitpass.event.repository.EventGateRepository;
import com.mitpass.event.repository.EventRepository;
import com.mitpass.event.repository.RegistrationRepository;
import com.mitpass.event.repository.VenueRepository;
import com.mitpass.event.repository.VolunteerAssignmentRepository;
import com.mitpass.security.test.SecurityMatrix;
import com.mitpass.security.test.SecurityMatrix.Rule;
import com.mitpass.security.test.TestJwtIssuer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every event-service endpoint × every kind of caller, with real tokens through the real filter chain and method
 * security. Prints the table and writes {@code target/security-matrix/event-service.md}.
 */
@EventIntegrationTest
class SecurityMatrixTest {

    static final String STUDENT = "student";
    static final String VOLUNTEER = "volunteer";
    static final String OWNER = "organizer (owner)";
    static final String OTHER = "organizer (other)";
    static final String ADMIN = "admin";

    static final UUID OWNER_ID = UUID.randomUUID();
    static final UUID STUDENT_ID = UUID.randomUUID();
    static final UUID VOLUNTEER_ID = UUID.randomUUID();
    static final AtomicInteger SEQ = new AtomicInteger();

    static final Map<String, String> TOKENS = new LinkedHashMap<>();

    static {
        TOKENS.put(STUDENT, TestJwtIssuer.tokenFor(STUDENT_ID, Roles.STUDENT));
        TOKENS.put(VOLUNTEER, TestJwtIssuer.tokenFor(VOLUNTEER_ID, Roles.STUDENT, Roles.VOLUNTEER));
        TOKENS.put(OWNER, TestJwtIssuer.tokenFor(OWNER_ID, Roles.ORGANIZER));
        TOKENS.put(OTHER, TestJwtIssuer.token(Roles.ORGANIZER));
        TOKENS.put(ADMIN, TestJwtIssuer.token(Roles.ADMIN));
    }

    /**
     * A fresh DRAFT event owned by OWNER with one session, one gate, VOLUNTEER assigned to it, and a registration that
     * belongs to STUDENT.
     */
    record Fixture(long event, long session, long gate, long venue, UUID registration, int n) {}

    record Endpoint(
            HttpMethod method,
            String template,
            Function<Fixture, String> path,
            Function<Fixture, String> body,
            Rule rule) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    EventRepository events;

    @Autowired
    CategoryRepository categories;

    @Autowired
    VenueRepository venues;

    @Autowired
    EventGateRepository gates;

    @Autowired
    VolunteerAssignmentRepository assignments;

    @Autowired
    RegistrationRepository registrations;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    FakeAuthService authService;

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        authService.register(
                new UserAccount(UUID.randomUUID(), "Matrix Volunteer", "matrix.vol@gmail.com", List.of("VOLUNTEER")));
        Rule ownerOrAdmin = Rule.only(OWNER, ADMIN);
        Rule organizers = Rule.only(OWNER, OTHER, ADMIN);

        List<Endpoint> endpoints = List.of(
                get("/api/events", f -> "/api/events", Rule.publicEndpoint()),
                get("/api/events/{id}", f -> "/api/events/" + f.event(), Rule.publicEndpoint()),
                write(HttpMethod.POST, "/api/events", f -> "/api/events", f -> event("MXC" + f.n()), organizers),
                write(
                        HttpMethod.PUT,
                        "/api/events/{id}",
                        f -> "/api/events/" + f.event(),
                        f -> event("MXU" + f.n()),
                        ownerOrAdmin),
                write(HttpMethod.DELETE, "/api/events/{id}", f -> "/api/events/" + f.event(), f -> null, ownerOrAdmin),
                get("/api/events/{id}/sessions", f -> "/api/events/" + f.event() + "/sessions", Rule.publicEndpoint()),
                write(
                        HttpMethod.POST,
                        "/api/events/{id}/sessions",
                        f -> "/api/events/" + f.event() + "/sessions",
                        f -> session("2099-12-05T06:00:00Z"),
                        ownerOrAdmin),
                write(
                        HttpMethod.PUT,
                        "/api/events/{id}/sessions/{sid}",
                        f -> "/api/events/" + f.event() + "/sessions/" + f.session(),
                        f -> session("2099-12-05T05:00:00Z"),
                        ownerOrAdmin),
                write(
                        HttpMethod.DELETE,
                        "/api/events/{id}/sessions/{sid}",
                        f -> "/api/events/" + f.event() + "/sessions/" + f.session(),
                        f -> null,
                        ownerOrAdmin),
                get("/api/venues", f -> "/api/venues", Rule.publicEndpoint()),
                write(
                        HttpMethod.POST,
                        "/api/venues",
                        f -> "/api/venues",
                        f -> venue("Matrix Hall " + f.n()),
                        organizers),
                write(
                        HttpMethod.PUT,
                        "/api/venues/{id}",
                        f -> "/api/venues/" + f.venue(),
                        f -> venue("Matrix Venue " + f.n()),
                        Rule.only(ADMIN)),
                get("/api/categories", f -> "/api/categories", Rule.publicEndpoint()),
                get("/api/events/{id}/gates", f -> "/api/events/" + f.event() + "/gates", Rule.authenticated()),
                write(
                        HttpMethod.POST,
                        "/api/events/{id}/gates",
                        f -> "/api/events/" + f.event() + "/gates",
                        f -> "{\"code\":\"NORTH\",\"name\":\"North Gate\"}",
                        ownerOrAdmin),
                write(
                        HttpMethod.DELETE,
                        "/api/events/{id}/gates/{gid}",
                        f -> "/api/events/" + f.event() + "/gates/" + f.gate(),
                        f -> null,
                        ownerOrAdmin),
                get("/api/events/{id}/volunteers", f -> "/api/events/" + f.event() + "/volunteers", ownerOrAdmin),
                write(
                        HttpMethod.POST,
                        "/api/events/{id}/volunteers",
                        f -> "/api/events/" + f.event() + "/volunteers",
                        f -> "{\"email\":\"matrix.vol@gmail.com\"}",
                        ownerOrAdmin),
                write(
                        HttpMethod.DELETE,
                        "/api/events/{id}/volunteers/{vid}",
                        f -> "/api/events/" + f.event() + "/volunteers/" + VOLUNTEER_ID,
                        f -> null,
                        ownerOrAdmin),
                get("/api/volunteers/me/assignments", f -> "/api/volunteers/me/assignments", Rule.only(VOLUNTEER)),
                // Phase 3: lifecycle and registrations (docs/plans/phase-3.md §7)
                write(
                        HttpMethod.POST,
                        "/api/events/{id}/transitions",
                        f -> "/api/events/" + f.event() + "/transitions",
                        f -> "{\"to\":\"PUBLISHED\"}",
                        ownerOrAdmin),
                get("/api/events/{id}/history", f -> "/api/events/" + f.event() + "/history", ownerOrAdmin),
                get("/api/events/{id}/registrations", f -> "/api/events/" + f.event() + "/registrations", ownerOrAdmin),
                get(
                        "/api/events/{id}/eligibility",
                        f -> "/api/events/" + f.event() + "/eligibility",
                        Rule.authenticated()),
                // The volunteer token also holds STUDENT. The fixture event is a DRAFT, so an allowed call gets
                // 409 registration-closed: past security, which is what the matrix checks.
                write(
                        HttpMethod.POST,
                        "/api/registrations",
                        f -> "/api/registrations",
                        f -> "{\"eventId\":%d}".formatted(f.event()),
                        Rule.only(STUDENT, VOLUNTEER)),
                get("/api/registrations/me", f -> "/api/registrations/me", Rule.authenticated()),
                get(
                        "/api/registrations/{id}",
                        f -> "/api/registrations/" + f.registration(),
                        Rule.only(STUDENT, OWNER, ADMIN)),
                write(
                        HttpMethod.POST,
                        "/api/registrations/{id}/cancel",
                        f -> "/api/registrations/" + f.registration() + "/cancel",
                        f -> null,
                        Rule.only(STUDENT, OWNER, ADMIN)));

        List<String> callers = List.of(ANONYMOUS, STUDENT, VOLUNTEER, OWNER, OTHER, ADMIN);
        SecurityMatrix matrix = new SecurityMatrix("event-service", callers);
        for (Endpoint endpoint : endpoints) {
            for (String caller : callers) {
                Fixture fixture = newFixture();
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                        endpoint.method(), endpoint.path().apply(fixture));
                String body = endpoint.body().apply(fixture);
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).content(body);
                }
                if (!ANONYMOUS.equals(caller)) {
                    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.get(caller));
                }
                MockHttpServletResponse response =
                        mvc.perform(request).andReturn().getResponse();
                matrix.record(
                        endpoint.method() + " " + endpoint.template(),
                        endpoint.rule(),
                        caller,
                        response.getStatus(),
                        response.getContentAsString());
            }
        }

        System.out.println(matrix.toMarkdown());
        matrix.writeTo(Path.of("target", "security-matrix", "event-service.md"));
        assertThat(matrix.failures()).isEmpty();
        assertThat(matrix.size()).isEqualTo(endpoints.size() * callers.size());
    }

    private Fixture newFixture() {
        int n = SEQ.incrementAndGet();
        return transaction.execute(status -> {
            Category category = categories.findAll().getFirst();
            Venue venue = venues.save(new Venue("Matrix Fixture Venue " + n, "Block M", 1000, null));
            Instant start = Instant.parse("2099-12-05T04:30:00Z");
            Event event = TestData.event("MXF" + n, category, venue, start);
            event.assignOrganizer(OWNER_ID, "Matrix Owner");
            EventSession session = new EventSession("Morning", start, start.plusSeconds(3600));
            event.addSession(session);
            events.saveAndFlush(event);
            EventGate gate = gates.save(new EventGate(event, "MAIN", "Main Gate"));
            assignments.save(new VolunteerAssignment(
                    event, gate, VOLUNTEER_ID, "Matrix Volunteer", "matrix.self@gmail.com", Instant.now(), "seed"));
            Registration registration = new Registration(
                    event,
                    new CurrentUser(
                            STUDENT_ID,
                            "matrix.student@test",
                            "Matrix Student",
                            Set.of("STUDENT"),
                            "CSE",
                            2,
                            null,
                            ""));
            registration.confirm(Instant.now(), false);
            registrations.save(registration);
            return new Fixture(event.getId(), session.getId(), gate.getId(), venue.getId(), registration.getId(), n);
        });
    }

    private static Endpoint get(String template, Function<Fixture, String> path, Rule rule) {
        return new Endpoint(HttpMethod.GET, template, path, f -> null, rule);
    }

    private static Endpoint write(
            HttpMethod method,
            String template,
            Function<Fixture, String> path,
            Function<Fixture, String> body,
            Rule rule) {
        return new Endpoint(method, template, path, body, rule);
    }

    private static String event(String code) {
        return """
                {"code":"%s","title":"Matrix event","categoryId":1,"venueId":1,"capacity":50,
                 "startsAt":"2099-12-05T04:30:00Z","endsAt":"2099-12-05T11:30:00Z"}
                """.formatted(code);
    }

    private static String session(String start) {
        return """
                {"title":"Late morning","startsAt":"%s","endsAt":"2099-12-05T07:00:00Z"}
                """.formatted(start);
    }

    private static String venue(String name) {
        return "{\"name\":\"%s\",\"building\":\"Block M\",\"capacity\":400}".formatted(name);
    }
}
