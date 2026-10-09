package com.mitpass.event.repository;

import static com.mitpass.event.TestData.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.event.TestcontainersConfiguration;
import com.mitpass.event.config.JpaConfig;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.service.EventSearchCriteria;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@ActiveProfiles("test")
class EventSpecificationsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    EventRepository events;

    @Autowired
    CategoryRepository categories;

    @Autowired
    VenueRepository venues;

    @BeforeEach
    void fixtures() {
        Category technical = category("TECHNICAL");
        Category cultural = category("CULTURAL");
        Venue lab = venue("Innovation Centre Lab");
        Venue theatre = venue("Open Air Theatre");

        Event hack = event("HACK26", technical, lab, Instant.parse("2026-11-14T03:30:00Z"));
        hack.setTitle("TechFest Hackathon");
        hack.setDepartment("CSE");
        Event night = event("RANG26", cultural, theatre, Instant.parse("2026-11-21T12:30:00Z"));
        night.setTitle("Rang: Cultural Night");
        night.setDescription("Music and a 100% hackable light show");
        // 2026-12-01T18:45Z is 00:15 on 2 December in IST: the day boundary must follow the campus zone, not UTC.
        Event midnight = event("LATE26", technical, lab, Instant.parse("2026-12-01T18:45:00Z"));
        midnight.setTitle("Midnight Code Golf");
        midnight.setDepartment("ece");
        events.saveAllAndFlush(List.of(hack, night, midnight));
    }

    @Test
    void noFiltersReturnsEverythingSortedAndPaged() {
        Page<Event> page = search(criteria(null, null, null, null, null), PageRequest.of(0, 2, Sort.by("startsAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Event::getCode).containsExactly("HACK26", "RANG26");
    }

    @Test
    void textSearchIsCaseInsensitiveAndCoversDescription() {
        assertThat(codes(criteria("HACK", null, null, null, null))).containsExactlyInAnyOrder("HACK26", "RANG26");
    }

    @Test
    void likeWildcardsInTheQueryAreLiteral() {
        assertThat(codes(criteria("100%", null, null, null, null))).containsExactly("RANG26");
        assertThat(codes(criteria("%", null, null, null, null))).containsExactly("RANG26");
    }

    @Test
    void filtersByCategoryCodeIgnoringCase() {
        assertThat(codes(criteria(null, "technical", null, null, null))).containsExactlyInAnyOrder("HACK26", "LATE26");
    }

    @Test
    void filtersByDepartmentIgnoringCase() {
        assertThat(codes(criteria(null, null, "ECE", null, null))).containsExactly("LATE26");
    }

    @Test
    void dateRangeUsesCampusDaysInclusive() {
        assertThat(codes(criteria(null, null, null, LocalDate.of(2026, 12, 2), null)))
                .containsExactly("LATE26");
        assertThat(codes(criteria(null, null, null, null, LocalDate.of(2026, 12, 1))))
                .containsExactlyInAnyOrder("HACK26", "RANG26");
        assertThat(codes(criteria(null, null, null, LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14))))
                .containsExactly("HACK26");
    }

    @Test
    void filtersCombineWithAnd() {
        assertThat(codes(criteria("code", "TECHNICAL", "ece", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))))
                .containsExactly("LATE26");
        assertThat(codes(criteria("hackathon", "CULTURAL", null, null, null))).isEmpty();
    }

    private static EventSearchCriteria criteria(
            String q, String category, String department, LocalDate from, LocalDate to) {
        return new EventSearchCriteria(q, category, department, from, to, null);
    }

    private Page<Event> search(EventSearchCriteria criteria, PageRequest page) {
        return events.findAll(EventSpecifications.matching(criteria, IST), page);
    }

    private List<String> codes(EventSearchCriteria criteria) {
        return search(criteria, PageRequest.of(0, 50)).map(Event::getCode).getContent();
    }

    private Category category(String code) {
        return categories.findAll().stream()
                .filter(c -> c.getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private Venue venue(String name) {
        return venues.findAll().stream()
                .filter(v -> v.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
