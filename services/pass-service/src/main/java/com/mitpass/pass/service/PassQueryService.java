package com.mitpass.pass.service;

import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.repository.PassChangeRepository;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.pass.repository.RevocationRepository;
import com.mitpass.pass.web.dto.PassDtos.PassChangeResponse;
import com.mitpass.pass.web.dto.PassDtos.RevocationEntry;
import com.mitpass.pass.web.dto.PassDtos.RevocationFeed;
import com.mitpass.pass.web.dto.PassResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use cases: wallets, organizer lists, history, the revocation feed. */
@Service
@Transactional(readOnly = true)
public class PassQueryService {

    private final PassRepository passes;
    private final PassChangeRepository history;
    private final RevocationRepository revocations;
    private final PassPolicy policy;

    public PassQueryService(
            PassRepository passes, PassChangeRepository history, RevocationRepository revocations, PassPolicy policy) {
        this.passes = passes;
        this.history = history;
        this.revocations = revocations;
        this.policy = policy;
    }

    public PageResponse<PassResponse> mine(UUID userId, PassStatus status, Pageable pageable) {
        return PageResponse.from(
                status == null
                        ? passes.findByUserId(userId, pageable)
                        : passes.findByUserIdAndStatus(userId, status, pageable),
                this::toResponse);
    }

    public PageResponse<PassResponse> forEvent(long eventId, PassStatus status, Pageable pageable) {
        return PageResponse.from(
                status == null
                        ? passes.findByEvent_Id(eventId, pageable)
                        : passes.findByEvent_IdAndStatus(eventId, status, pageable),
                this::toResponse);
    }

    public PassResponse get(UUID id) {
        return toResponse(load(id));
    }

    public List<PassChangeResponse> history(UUID id) {
        load(id);
        return history.findByPass_IdOrderByChangedAtAscIdAsc(id).stream()
                .map(PassChangeResponse::from)
                .toList();
    }

    public RevocationFeed revocations(long since, Long eventId, int limit) {
        List<RevocationEntry> items = (eventId == null
                        ? revocations.findBySeqGreaterThanOrderBySeq(since, Limit.of(limit))
                        : revocations.findByEventIdAndSeqGreaterThanOrderBySeq(eventId, since, Limit.of(limit)))
                .stream().map(RevocationEntry::from).toList();
        long next = items.isEmpty() ? since : items.getLast().seq();
        return new RevocationFeed(items, next);
    }

    public PassResponse toResponse(Pass pass) {
        return PassResponse.from(pass, policy.unlocksAt(pass.getEvent()));
    }

    Pass load(UUID id) {
        return passes.findDetailedById(id).orElseThrow(() -> NotFoundException.of("Pass", id));
    }
}
