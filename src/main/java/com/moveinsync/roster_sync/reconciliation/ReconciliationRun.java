package com.moveinsync.roster_sync.reconciliation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;

public record ReconciliationRun(
        long id,
        String tenantId,
        String status,
        @JsonIgnore String filePath,  // internal server path: never sent to clients
        ReconciliationSummary summary,
        String approvedBy,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {
}