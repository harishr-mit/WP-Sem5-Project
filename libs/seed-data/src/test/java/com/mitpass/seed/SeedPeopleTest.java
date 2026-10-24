package com.mitpass.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.seed.SeedPeople.Person;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SeedPeopleTest {

    @Test
    void theSameCallGivesTheSamePeopleAndTheListIsAPrefixOfALongerOne() {
        List<Person> a = SeedPeople.students(2000);
        assertThat(SeedPeople.students(2000)).isEqualTo(a);
        assertThat(SeedPeople.students(50)).isEqualTo(a.subList(0, 50));
        assertThat(SeedPeople.volunteers(40)).isEqualTo(SeedPeople.volunteers(40));
    }

    @Test
    void idsEmailsAndRegisterNumbersAreUniqueAndFitAuthServicesRules() {
        List<Person> all = Stream.concat(SeedPeople.students(2000).stream(), SeedPeople.volunteers(40).stream())
                .toList();
        assertThat(all).extracting(Person::id).doesNotHaveDuplicates();
        assertThat(all).extracting(Person::email).doesNotHaveDuplicates();
        assertThat(SeedPeople.students(2000)).allSatisfy(p -> {
            assertThat(p.registerNumber()).matches("\\d{10}");
            assertThat(p.email()).isEqualTo(p.registerNumber() + "@student.annauniv.edu");
            assertThat(p.year()).isBetween(1, 4);
            assertThat(SeedPeople.DEPARTMENTS).contains(p.department());
            assertThat(p.fullName()).isNotBlank().hasSizeLessThanOrEqualTo(120);
        });
        assertThat(SeedPeople.students(2000)).extracting(Person::registerNumber).doesNotHaveDuplicates();
    }
}
