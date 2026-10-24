package com.mitpass.seed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.datafaker.Faker;

/**
 * The fake campus for the {@code bulk-seed} profile (docs/DECISIONS.md D17): students and volunteers whose ids, names,
 * departments, years and register numbers are the same wherever they are generated, because both auth-service (which
 * creates the accounts) and event-service (which registers them for events) call this. Everyone's password is
 * {@link #PASSWORD} (D8).
 */
public final class SeedPeople {

    public static final String PASSWORD = "Passw0rd!";

    /** The department codes of auth-service's reference data (V2), in a fixed order. */
    public static final List<String> DEPARTMENTS =
            List.of("CSE", "IT", "ECE", "EIE", "MECH", "AERO", "AUTO", "PROD", "RPT", "ROBO", "AIDS");

    private static final long SEED = 26;

    public record Person(
            UUID id,
            String email,
            String fullName,
            String department,
            int year,
            String registerNumber,
            boolean volunteer) {}

    private SeedPeople() {}

    /** Students {@code 1..count}, each with a 10-digit register number {@code 2027<programme><roll>}. */
    public static List<Person> students(int count) {
        Faker faker = new Faker(Locale.of("en", "IN"), new Random(SEED));
        int[] perDepartment = new int[DEPARTMENTS.size()];
        List<Person> people = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int d = (i - 1) % DEPARTMENTS.size();
            int roll = ++perDepartment[d];
            String regNo = "2027%d%03d".formatted(500 + d, roll);
            people.add(new Person(
                    id("student", i),
                    regNo + "@student.annauniv.edu",
                    name(faker),
                    DEPARTMENTS.get(d),
                    1 + (i * 7) % 4,
                    regNo,
                    false));
        }
        return List.copyOf(people);
    }

    /** Volunteers {@code 1..count}: students who also scan; no register number, a staff-style address. */
    public static List<Person> volunteers(int count) {
        Faker faker = new Faker(Locale.of("en", "IN"), new Random(SEED + 1));
        List<Person> people = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            people.add(new Person(
                    id("volunteer", i),
                    "volunteer%02d@mitpass.test".formatted(i),
                    name(faker),
                    DEPARTMENTS.get((i - 1) % DEPARTMENTS.size()),
                    2 + i % 3,
                    null,
                    true));
        }
        return List.copyOf(people);
    }

    private static String name(Faker faker) {
        String full = faker.name().firstName() + " " + faker.name().lastName();
        return full.length() <= 120 ? full : full.substring(0, 120);
    }

    /** A stable UUID per kind and number, so a re-run (and the other service) get the same one. */
    static UUID id(String kind, int number) {
        return UUID.nameUUIDFromBytes(("mitpass-seed-" + kind + "-" + number).getBytes(StandardCharsets.UTF_8));
    }
}
