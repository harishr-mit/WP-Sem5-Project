package com.mitpass.checkin;

import com.mitpass.checkin.client.PassDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.client.ResourceAccessException;

/**
 * pass-service as the reconciler sees it: passes and a revocation feed in memory. Nothing here reaches checkin-service
 * by message, so a test can prove the reconciler finds what messages missed. {@link #down} simulates an outage.
 */
public class FakePassDirectory implements PassDirectory {

    private final Map<UUID, PassRecord> passes = new ConcurrentHashMap<>();
    private final List<RevocationItem> revocations = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean down;

    public void put(PassRecord pass) {
        passes.put(pass.id(), pass);
    }

    /** Revokes in the owner and appends to its feed, like pass-service does. */
    public void revoke(UUID passId, Instant at) {
        PassRecord pass = passes.get(passId);
        long eventId = pass == null ? 0 : pass.eventId();
        if (pass != null) {
            passes.put(
                    passId,
                    new PassRecord(
                            pass.id(),
                            pass.passNumber(),
                            "REVOKED",
                            pass.registrationId(),
                            pass.eventId(),
                            pass.userId(),
                            pass.holderName(),
                            pass.holderEmail(),
                            pass.issuedAt(),
                            at,
                            "ORGANIZER"));
        }
        revocations.add(new RevocationItem(revocations.size() + 1L, passId, eventId, at, "ORGANIZER"));
    }

    public void clear() {
        passes.clear();
        revocations.clear();
        calls.set(0);
        down = false;
    }

    @Override
    public Optional<PassRecord> pass(UUID passId) {
        hit();
        return Optional.ofNullable(passes.get(passId));
    }

    @Override
    public PassPage passesOf(long eventId, int page, int size) {
        hit();
        List<PassRecord> all = new ArrayList<>(passes.values().stream()
                .filter(p -> p.eventId() == eventId)
                .sorted(Comparator.comparing(PassRecord::passNumber))
                .toList());
        int totalPages = (all.size() + size - 1) / size;
        int from = Math.min(page * size, all.size());
        return new PassPage(all.subList(from, Math.min(from + size, all.size())), page, totalPages);
    }

    @Override
    public RevocationPage revocationsSince(long cursor, int limit) {
        hit();
        List<RevocationItem> items =
                revocations.stream().filter(r -> r.seq() > cursor).limit(limit).toList();
        return new RevocationPage(
                items, items.isEmpty() ? cursor : items.getLast().seq());
    }

    private void hit() {
        calls.incrementAndGet();
        if (down) {
            throw new ResourceAccessException("pass-service is down (test)");
        }
    }
}
