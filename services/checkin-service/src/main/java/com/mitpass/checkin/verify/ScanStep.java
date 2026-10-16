package com.mitpass.checkin.verify;

import com.mitpass.checkin.domain.ScanResult;
import java.util.Map;
import java.util.Optional;

/**
 * One link of the verification chain (docs/plans/phase-5.md §4). Steps run in {@code @Order}; the first rejection
 * ends the scan. A step learns facts into the {@link ScanContext} for the steps after it.
 */
public interface ScanStep {

    /** Does this step apply to the scan (some only to tokens, some only to typed numbers)? */
    default boolean appliesTo(ScanContext scan) {
        return true;
    }

    /** Empty to continue; a rejection to stop. */
    Optional<Rejection> check(ScanContext scan);

    /**
     * Why a scan stops: the result, the values its message needs ({@code name}, {@code time}, {@code gate},
     * {@code event}) and a short note for the scan log.
     */
    record Rejection(ScanResult result, Map<String, String> details, String note) {

        public static Optional<Rejection> of(ScanResult result, String note) {
            return Optional.of(new Rejection(result, Map.of(), note));
        }

        public static Optional<Rejection> of(ScanResult result, Map<String, String> details, String note) {
            return Optional.of(new Rejection(result, details, note));
        }
    }
}
