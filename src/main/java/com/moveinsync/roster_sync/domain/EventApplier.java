package com.moveinsync.roster_sync.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Applies one HR event to the current employee state.
 *
 * Rules, in order:
 *   1. event.version == stored hrVersion  -> DUPLICATE (no-op)
 *   2. event.version <  stored hrVersion  -> STALE     (no-op)
 *   3. otherwise build the new state from the event:
 *        - HR fields come from the event (full snapshot)
 *        - transport fields are always copied from current state (HR never owns them)
 *        - DEACTIVATE keeps the last known HR fields and sets INACTIVE
 *   4. diff old vs new: nothing changed -> NO_CHANGE, else APPLIED
 *
 * current may be null (employee not seen before).
 */
public final class EventApplier {

    public ApplyResult apply(Employee current, EmployeeEvent event) {
        if (current != null) {
            if (event.version() == current.hrVersion()) {
                return new ApplyResult(ApplyOutcome.DUPLICATE, current, Set.of());
            }
            if (event.version() < current.hrVersion()) {
                return new ApplyResult(ApplyOutcome.STALE, current, Set.of());
            }
        }

        HrFields previousHr = current == null ? HrFields.EMPTY : current.hr();
        TransportFields transport = current == null ? TransportFields.EMPTY : current.transport();
        long adminVersion = current == null ? 0 : current.adminVersion();

        HrFields nextHr;
        EmployeeStatus nextStatus;
        if (event.type() == EventType.DEACTIVATE) {
            nextHr = previousHr;
            nextStatus = EmployeeStatus.INACTIVE;
        } else {
            nextHr = event.hr();
            nextStatus = EmployeeStatus.ACTIVE;
        }

        Employee next = new Employee(
                event.tenantId(), event.employeeId(), nextStatus, event.version(),
                nextHr, transport, adminVersion);

        Set<String> changed = changedFields(current, next);
        ApplyOutcome outcome = changed.isEmpty() ? ApplyOutcome.NO_CHANGE : ApplyOutcome.APPLIED;
        return new ApplyResult(outcome, next, Collections.unmodifiableSet(changed));
    }

    private static Set<String> changedFields(Employee before, Employee after) {
        Set<String> changed = new LinkedHashSet<>();
        HrFields b = before == null ? HrFields.EMPTY : before.hr();
        HrFields a = after.hr();

        if (before == null || before.status() != after.status()) {
            changed.add("status");
        }
        compare("name", b.name(), a.name(), changed);
        compare("email", b.email(), a.email(), changed);
        compare("phone", b.phone(), a.phone(), changed);
        compare("managerId", b.managerId(), a.managerId(), changed);
        compare("officeCode", b.officeCode(), a.officeCode(), changed);
        compare("shiftCode", b.shiftCode(), a.shiftCode(), changed);
        return changed;
    }

    private static void compare(String field, Object before, Object after, Set<String> changed) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }
}