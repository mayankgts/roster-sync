package com.moveinsync.roster_sync.reconciliation;

import com.moveinsync.roster_sync.service.RecordResult;
import java.util.List;

/** The run plus the first rejected rows (index = line number in the file). */
public record ReconciliationReport(ReconciliationRun run, List<RecordResult> problems) {
}