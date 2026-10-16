package com.mitpass.pass.repository;

import com.mitpass.pass.domain.EventRef;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads only: rows are written by {@link EventRefStore}. */
public interface EventRefRepository extends JpaRepository<EventRef, Long> {}
