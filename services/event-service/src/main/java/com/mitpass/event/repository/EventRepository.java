package com.mitpass.event.repository;

import com.mitpass.event.domain.Event;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Fetch plans per use case (the entity maps every association LAZY):
 *
 * <ul>
 *   <li>search/list: {@code category + venue} in the same query, no sessions (fetching a collection while paginating
 *       would make Hibernate paginate in memory);
 *   <li>detail: {@code category + venue + sessions}.
 * </ul>
 */
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    @Override
    @EntityGraph(attributePaths = {"category", "venue"})
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "venue", "sessions"})
    Optional<Event> findDetailedById(Long id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}
