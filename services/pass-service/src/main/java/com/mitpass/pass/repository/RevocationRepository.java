package com.mitpass.pass.repository;

import com.mitpass.pass.domain.Revocation;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevocationRepository extends JpaRepository<Revocation, Long> {

    List<Revocation> findBySeqGreaterThanOrderBySeq(long since, Limit limit);

    List<Revocation> findByEventIdAndSeqGreaterThanOrderBySeq(long eventId, long since, Limit limit);
}
