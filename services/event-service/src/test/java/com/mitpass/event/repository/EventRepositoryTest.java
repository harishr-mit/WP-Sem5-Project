package com.mitpass.event.repository;

import static com.mitpass.event.TestData.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.event.TestcontainersConfiguration;
import com.mitpass.event.config.JpaConfig;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventSession;
import com.mitpass.event.domain.EventStatus;
import com.mitpass.event.domain.Venue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/** Runs against real PostgreSQL: Flyway V1/V2 must apply and Hibernate's {@code ddl-auto=validate} must accept them. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@ActiveProfiles("test")
class EventRepositoryTest {

    private static final Instant START = Instant.parse("2026-11-14T03:30:00Z");

    @Autowired
    EventRepository events;

    @Autowired
    CategoryRepository categories;

    @Autowired
    VenueRepository venues;

    @Autowired
    EntityManager em;

    Category technical;
    Venue lab;

    @BeforeEach
    void referenceData() {
        technical = categories.findAll().stream()
                .filter(c -> c.getCode().equals("TECHNICAL"))
                .findFirst()
                .orElseThrow();
        lab = venues.findAll().stream()
                .filter(v -> v.getName().equals("Innovation Centre Lab"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void flywaySeedsReferenceData() {
        assertThat(categories.findAllByOrderByNameAsc())
                .extracting(Category::getCode)
                .containsExactly("CLUB", "CULTURAL", "SEMINAR", "SPORTS", "TECHNICAL", "WORKSHOP");
        assertThat(venues.count()).isEqualTo(5);
    }

    @Test
    void savingAnEventCascadesSessionsAndFillsAuditColumns() {
        Event hackathon = event("HACK26", technical, lab, START);
        hackathon.addSession(new EventSession("Day 1", START, START.plusSeconds(3600)));
        hackathon.addSession(new EventSession("Day 2", START.plusSeconds(7200), START.plusSeconds(10800)));

        Event saved = events.saveAndFlush(hackathon);
        em.clear();

        Event loaded = events.findDetailedById(saved.getId()).orElseThrow();
        assertThat(loaded.getSessions()).extracting(EventSession::getTitle).containsExactly("Day 1", "Day 2");
        assertThat(loaded.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedBy()).isEqualTo("system");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void entityGraphsDecideWhatIsLoaded() {
        Event saved = events.saveAndFlush(event("GRAPH26", technical, lab, START));
        em.clear();
        PersistenceUnitUtil util = em.getEntityManagerFactory().getPersistenceUnitUtil();

        Event plain = events.findById(saved.getId()).orElseThrow();
        assertThat(util.isLoaded(plain, "sessions")).isFalse();
        assertThat(util.isLoaded(plain.getCategory())).isFalse(); // LAZY proxy, not fetched
        em.clear();

        Event detailed = events.findDetailedById(saved.getId()).orElseThrow();
        assertThat(util.isLoaded(detailed, "sessions")).isTrue();
        assertThat(util.isLoaded(detailed.getCategory())).isTrue();
        assertThat(util.isLoaded(detailed.getVenue())).isTrue();
    }

    @Test
    void databaseRejectsALowercaseCode() {
        assertThatThrownBy(() -> events.saveAndFlush(event("bad-code", technical, lab, START)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void codeUniquenessQueries() {
        Event saved = events.saveAndFlush(event("UNIQ26", technical, lab, START));

        assertThat(events.existsByCode("UNIQ26")).isTrue();
        assertThat(events.existsByCodeAndIdNot("UNIQ26", saved.getId())).isFalse();
        assertThat(events.existsByCodeAndIdNot("UNIQ26", -1L)).isTrue();
    }
}
