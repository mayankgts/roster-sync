package com.moveinsync.roster_sync.reconciliation;

public record ReconciliationSummary(
        int fileRows,
        int rejectedRows,
        int activeBefore,
        int dirtyBuckets,
        int changedRows,
        int missing,
        double missingRatio,
        double brakeThreshold,
        int applied,
        int noChange,
        int staleOrDuplicate,
        int deactivated,
        int skippedConcurrentChange,
        int failed,
        long durationMs) {
}