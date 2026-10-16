package com.mitpass.pass.repository;

import com.mitpass.pass.domain.PassChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PassChangeRepository extends JpaRepository<PassChange, Long> {

    List<PassChange> findByPass_IdOrderByChangedAtAscIdAsc(UUID passId);
}
