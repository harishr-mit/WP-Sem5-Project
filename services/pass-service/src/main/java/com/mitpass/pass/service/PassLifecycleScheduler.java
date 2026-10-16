package com.mitpass.pass.service;

import com.mitpass.pass.config.PassProperties;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.pass.repository.PassRepository.PassRef;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-based pass moves (docs/plans/phase-4.md §4): ISSUED → ACTIVE when the live code unlocks, ISSUED/ACTIVE →
 * EXPIRED after the event (a fallback for a missing COMPLETED). One transaction per event; a pass changed meanwhile by
 * a consumer or an organizer fails its {@code @Version} check and is skipped (same design as event-service, D10).
 * ShedLock for several instances comes in P7. The bean always exists (tests call {@link #runOnce}); only the
 * {@code @Scheduled} trigger depends on {@code mitpass.passes.scheduler.enabled} (AppConfig).
 */
@Component
public class PassLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(PassLifecycleScheduler.class);

    private final PassRepository passes;
    private final PassLifecycleService lifecycle;
    private final PassProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PassLifecycleScheduler(
            PassRepository passes,
            PassLifecycleService lifecycle,
            PassProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.passes = passes;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mitpass.passes.scheduler.interval:30s}")
    public void run() {
        runOnce(clock.instant());
    }

    /** @return passes moved (activated + expired) */
    public int runOnce(Instant now) {
        int expired = apply(passes.findDueForExpiry(now.minus(properties.expireAfterEnd())), PassStatus.EXPIRED);
        int activated = apply(
                passes.findDueForActivation(
                        now.plus(properties.codeUnlockBeforeStart()), now.minus(properties.expireAfterEnd())),
                PassStatus.ACTIVE);
        if (activated + expired > 0) {
            log.info("Pass scheduler: {} activated, {} expired", activated, expired);
        }
        return activated + expired;
    }

    private int apply(List<PassRef> due, PassStatus target) {
        Map<Long, List<UUID>> byEvent = new LinkedHashMap<>();
        due.forEach(ref -> byEvent.computeIfAbsent(ref.eventId(), id -> new java.util.ArrayList<>())
                .add(ref.id()));
        int moved = 0;
        for (Map.Entry<Long, List<UUID>> event : byEvent.entrySet()) {
            try {
                transactions.executeWithoutResult(
                        status -> event.getValue().forEach(id -> lifecycle.activateOrExpire(id, target)));
                moved += event.getValue().size();
            } catch (OptimisticLockingFailureException ex) {
                log.debug("Event {}: a pass changed meanwhile; retrying next run", event.getKey());
            }
        }
        return moved;
    }
}
