package com.mitpass.event.repository;

import com.mitpass.event.domain.RegistrationChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationChangeRepository extends JpaRepository<RegistrationChange, Long> {}
