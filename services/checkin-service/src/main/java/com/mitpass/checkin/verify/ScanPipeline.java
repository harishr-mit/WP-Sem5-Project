package com.mitpass.checkin.verify;

import com.mitpass.checkin.verify.ScanStep.Rejection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link ScanStep} beans in {@code @Order} (chain of responsibility, docs/plans/phase-5.md §4). Spring hands
 * the list over sorted. The first rejection ends the chain; if none, the scan goes on to the check-in transaction.
 */
@Component
public class ScanPipeline {

    private final List<ScanStep> steps;

    public ScanPipeline(List<ScanStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public Optional<Rejection> run(ScanContext scan) {
        for (ScanStep step : steps) {
            if (step.appliesTo(scan)) {
                Optional<Rejection> rejection = step.check(scan);
                if (rejection.isPresent()) {
                    return rejection;
                }
            }
        }
        return Optional.empty();
    }

    /** The step classes in the order they run (for tests and docs). */
    public List<String> order() {
        return steps.stream().map(step -> step.getClass().getSimpleName()).toList();
    }
}
