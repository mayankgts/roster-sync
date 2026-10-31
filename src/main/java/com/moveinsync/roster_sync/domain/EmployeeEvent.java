package com.moveinsync.roster_sync.domain;

public record EmployeeEvent(
        String tenantId,
        String employeeId,
        EventType type,
        long version,
        HrFields hr) {

    public EmployeeEvent {
        if (tenantId == null || employeeId == null || type == null) {
            throw new IllegalArgumentException("tenantId, employeeId and type are required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (hr == null) {
            hr = HrFields.EMPTY;
        }
    }
}
