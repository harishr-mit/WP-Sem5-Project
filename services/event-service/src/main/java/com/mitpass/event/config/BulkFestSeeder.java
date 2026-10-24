package com.mitpass.event.config;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.TransitionTrigger;
import com.mitpass.event.service.EventLifecycleService;
import com.mitpass.event.service.EventService;
import com.mitpass.event.service.RegistrationService;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.seed.SeedPeople;
import com.mitpass.seed.SeedPeople.Person;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code bulk-seed} profile (docs/DECISIONS.md D17): a college fest. One big opening night (the load test's event,
 * four gates, forty volunteers) and eleven smaller events, 2,000 students registered through the real
 * {@link RegistrationService}, so seats, waitlists, the outbox, passes (pass-service), check-in copies and the "pass
 * issued" mails all happen exactly as for a real student. Nothing is written around the business rules except the
 * gates and volunteer assignments, which are this service's own rows (the API would need a user's token).
 *
 * <p>Runs once (skipped when the opening night exists) on its own thread, after the other services have declared their
 * queues (docker-compose.yml starts event-service last): a message published before its queue exists is dropped.
 */
@Component
@Profile("bulk-seed")
public class BulkFestSeeder implements ApplicationRunner {

    static final String FLAGSHIP = "FEST26OPEN";

    private static final Logger log = LoggerFactory.getLogger(BulkFestSeeder.class);

    private static final UUID ORGANIZER_A = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ORGANIZER_B = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private record Spec(String code, String title, int capacity, boolean waitlist, int daysAhead) {}

    private static final List<Spec> SMALL = List.of(
            new Spec("FEST26HACK", "Fest Hackathon", 300, false, 4),
            new Spec("FEST26ROBO", "Robo Wars (limited seats)", 20, true, 4),
            new Spec("FEST26AI", "AI Workshop", 150, true, 4),
            new Spec("FEST26QUIZ", "Tech Quiz Finals", 120, false, 5),
            new Spec("FEST26BAND", "Battle of the Bands", 250, false, 5),
            new Spec("FEST26DANC", "Dance Showdown", 200, true, 5),
            new Spec("FEST26CODE", "Competitive Coding", 180, false, 5),
            new Spec("FEST26ROCK", "Rocketry Talk", 90, false, 6),
            new Spec("FEST26ARTS", "Art Walk", 220, false, 6),
            new Spec("FEST26SPRT", "Inter-department Cricket", 40, true, 6),
            new Spec("FEST26CLOS", "Closing Ceremony", 280, false, 7));

    private final EventService events;
    private final EventLifecycleService lifecycle;
    private final RegistrationService registrations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int students;
    private final int volunteers;
    private final int flagshipRegistrations;
    private final Duration startDelay;

    public BulkFestSeeder(
            EventService events,
            EventLifecycleService lifecycle,
            RegistrationService registrations,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${mitpass.seed.bulk.students:2000}") int students,
            @Value("${mitpass.seed.bulk.volunteers:40}") int volunteers,
            @Value("${mitpass.seed.bulk.flagship-registrations:1100}") int flagshipRegistrations,
            @Value("${mitpass.seed.bulk.start-delay:60s}") Duration startDelay) {
        this.events = events;
        this.lifecycle = lifecycle;
        this.registrations = registrations;
        this.jdbc = jdbc;
        this.clock = clock;
        this.students = students;
        this.volunteers = volunteers;
        this.flagshipRegistrations = flagshipRegistrations;
        this.startDelay = startDelay;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT count(*) FROM event WHERE code = ?", Long.class, FLAGSHIP) > 0) {
            log.info("Bulk seed: the fest already exists, nothing to do");
            return;
        }
        Thread.ofPlatform().name("bulk-seed").start(() -> {
            try {
                // Eureka registration and the other services' load-balancer caches take up to a minute: a "pass
                // issued" mail built from a message that arrives sooner can't find event-service (it would be
                // dead-lettered).
                Thread.sleep(startDelay);
                seed();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                log.error("Bulk seed failed (the fest may be half-made: wipe with `docker compose down -v`)", ex);
            }
        });
    }

    void seed() {
        Instant now = clock.instant();
        Map<String, Long> ids = new java.util.LinkedHashMap<>();

        // The biggest venue hosts the opening night; the others rotate over the rest.
        List<Map<String, Object>> venues =
                jdbc.queryForList("SELECT id, capacity FROM venue ORDER BY capacity DESC, id");
        List<Long> categories = jdbc.queryForList("SELECT id FROM category ORDER BY id", Long.class);
        int bigVenueCapacity = ((Number) venues.get(0).get("capacity")).intValue();

        ids.put(
                FLAGSHIP,
                create(
                        new Spec(FLAGSHIP, "MITPass Fest: Opening Night", Math.min(bigVenueCapacity, 1200), false, 3),
                        ((Number) venues.get(0).get("id")).longValue(),
                        categories.get(0),
                        organizer(ORGANIZER_A),
                        now));
        for (int i = 0; i < SMALL.size(); i++) {
            Map<String, Object> venue = venues.get(i % venues.size());
            int venueCapacity = ((Number) venue.get("capacity")).intValue();
            Spec spec = SMALL.get(i);
            Spec fit = new Spec(
                    spec.code(),
                    spec.title(),
                    Math.min(spec.capacity(), venueCapacity),
                    spec.waitlist(),
                    spec.daysAhead());
            ids.put(
                    spec.code(),
                    create(
                            fit,
                            ((Number) venue.get("id")).longValue(),
                            categories.get(i % categories.size()),
                            organizer(i % 2 == 0 ? ORGANIZER_A : ORGANIZER_B),
                            now));
        }

        long flagship = ids.get(FLAGSHIP);
        String[] gates = {"MAIN", "NORTH", "SOUTH", "EAST"};
        long[] gateIds = new long[gates.length];
        for (int g = 0; g < gates.length; g++) {
            gateIds[g] = jdbc.queryForObject(
                    "INSERT INTO event_gate (event_id, code, name) VALUES (?, ?, ?) RETURNING id",
                    Long.class,
                    flagship,
                    gates[g],
                    gates[g].charAt(0) + gates[g].substring(1).toLowerCase() + " Gate");
        }
        List<Person> crew = SeedPeople.volunteers(volunteers);
        for (int i = 0; i < crew.size(); i++) {
            Person v = crew.get(i);
            jdbc.update(
                    """
                    INSERT INTO volunteer_assignment (event_id, gate_id, volunteer_id, volunteer_name, volunteer_email,
                                                      assigned_at, assigned_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'seed') ON CONFLICT DO NOTHING""",
                    flagship,
                    gateIds[i % gateIds.length],
                    v.id(),
                    v.fullName(),
                    v.email(),
                    java.sql.Timestamp.from(now));
        }

        List<Person> people = SeedPeople.students(students);
        int refused = 0;
        List<Long> small = SMALL.stream().map(s -> ids.get(s.code())).toList();
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            CurrentUser student = new CurrentUser(
                    p.id(),
                    p.email(),
                    p.fullName(),
                    Set.of("STUDENT"),
                    p.department(),
                    p.year(),
                    p.registerNumber(),
                    "seed");
            if (i < flagshipRegistrations && !register(student, flagship)) {
                refused++;
            }
            if (!register(student, small.get(i % small.size()))) {
                refused++;
            }
        }
        log.info(
                "Bulk seed done: {} events, {} students, {} registrations tried ({} refused: full events without a waitlist)",
                ids.size(),
                people.size(),
                people.size() + Math.min(flagshipRegistrations, people.size()),
                refused);
    }

    private boolean register(CurrentUser student, long eventId) {
        try {
            registrations.register(student, eventId);
            return true;
        } catch (RuntimeException ex) {
            return false; // a full event without a waitlist, or a student already in: expected for some
        }
    }

    private long create(Spec spec, long venueId, long categoryId, CurrentUser organizer, Instant now) {
        Instant starts = now.plus(Duration.ofDays(spec.daysAhead()));
        long id = events.create(
                        organizer,
                        new EventCreateRequest(
                                spec.code(),
                                spec.title(),
                                "Part of the MITPass college fest.",
                                categoryId,
                                venueId,
                                null,
                                spec.capacity(),
                                starts,
                                starts.plus(Duration.ofHours(3)),
                                now.minus(Duration.ofDays(1)),
                                starts.minus(Duration.ofDays(1)),
                                null,
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                spec.waitlist()))
                .id();
        lifecycle.transition(id, EventStatus.PUBLISHED, "seed", TransitionTrigger.MANUAL, null);
        lifecycle.transition(id, EventStatus.REGISTRATION_OPEN, "seed", TransitionTrigger.MANUAL, null);
        return id;
    }

    private static CurrentUser organizer(UUID id) {
        boolean a = ORGANIZER_A.equals(id);
        return new CurrentUser(
                id,
                a ? "organizer.a@mitpass.test" : "organizer.b@mitpass.test",
                a ? "Anitha Rao" : "Bharath Kumar",
                Set.of("ORGANIZER", "VOLUNTEER"),
                null,
                null,
                null,
                "seed");
    }
}
