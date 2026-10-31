package com.moveinsync.roster_sync.domain;

public record Employee(
        String tenantId,
        String employeeId,
        EmployeeStatus status,
        long hrVersion,
        HrFields hr,
        TransportFields transport,
        long adminVersion) {

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }
}