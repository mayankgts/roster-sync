package com.moveinsync.roster_sync.service;

import com.moveinsync.roster_sync.domain.Employee;

/** The kind of event written to the outbox. */
public enum ChangeType {
    EMPLOYEE_CREATED,
    EMPLOYEE_UPDATED,
    EMPLOYEE_DEACTIVATED,
    EMPLOYEE_REACTIVATED,
    TRANSPORT_UPDATED;

    public static ChangeType of(Employee before, Employee after) {
        if (before == null) {
            return after.isActive() ? EMPLOYEE_CREATED : EMPLOYEE_DEACTIVATED;
        }
        if (before.isActive() && !after.isActive()) {
            return EMPLOYEE_DEACTIVATED;
        }
        if (!before.isActive() && after.isActive()) {
            return EMPLOYEE_REACTIVATED;
        }
        return EMPLOYEE_UPDATED;
    }
}