package com.moveinsync.roster_sync.domain;

import java.util.Set;

/**
 * What happened when an event was applied. changedFields holds field
 * NAMES only (never values), so it is safe to log and to put in events.
 */
public record ApplyResult(ApplyOutcome outcome, Employee employee, Set<String> changedFields) {

    public boolean needsSave() {
        return outcome == ApplyOutcome.APPLIED || outcome == ApplyOutcome.NO_CHANGE;
    }

    public boolean needsEvent() {
        return outcome == ApplyOutcome.APPLIED;
    }
}