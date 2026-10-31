package com.moveinsync.roster_sync.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Counts for every outcome, plus details only for records that need attention. */
public record BatchResult(int total, Map<RecordOutcome, Integer> counts, List<RecordResult> problems) {

    public static BatchResult of(List<RecordResult> results) {
        Map<RecordOutcome, Integer> counts = new EnumMap<>(RecordOutcome.class);
        for (RecordOutcome outcome : RecordOutcome.values()) {
            counts.put(outcome, 0);
        }
        List<RecordResult> problems = new ArrayList<>();
        for (RecordResult result : results) {
            counts.merge(result.outcome(), 1, Integer::sum);
            if (result.isProblem()) {
                problems.add(result);
            }
        }
        return new BatchResult(results.size(), counts, problems);
    }
}