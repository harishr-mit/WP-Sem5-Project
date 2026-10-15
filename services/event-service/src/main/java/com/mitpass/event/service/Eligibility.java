package com.mitpass.event.service;

import java.util.List;

/**
 * Whether a student may register now.
 *
 * @param eligible registration is open and no rule excludes the student
 * @param registrationOpen the event takes registrations at this moment (state and closing time)
 * @param reasons why not, in words a student understands; empty when eligible
 */
public record Eligibility(boolean eligible, boolean registrationOpen, List<String> reasons) {

    public Eligibility {
        reasons = List.copyOf(reasons);
    }
}
