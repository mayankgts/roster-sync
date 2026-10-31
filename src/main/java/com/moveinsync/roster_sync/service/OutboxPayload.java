package com.moveinsync.roster_sync.service;

import java.util.Set;

/** What downstream consumers receive. IDs and codes only, no personal data. */
public record OutboxPayload(
        String employeeId,
        long version,
        String status,
        String officeCode,
        String shiftCode,
        Set<String> changedFields) {
}