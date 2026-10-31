package com.moveinsync.roster_sync.service;

import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.TransportFields;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.persistence.OutboxRepository;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The transport admin's write path. Only ever touches transport-owned fields. */
@Service
public class TransportService {

    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;

    public TransportService(EmployeeRepository employees, OutboxRepository outbox,
                            PlatformTransactionManager txManager) {
        this.employees = employees;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
    }

    public Employee find(String tenantId, String employeeId) {
        return employees.find(tenantId, employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
    }

    public Employee update(String tenantId, String employeeId, TransportFields requested) {
        return tx.execute(status -> {
            employees.lock(tenantId, employeeId);
            Employee current = find(tenantId, employeeId);

            Set<String> changed = new LinkedHashSet<>();
            if (!Objects.equals(current.transport().pickupPoint(), requested.pickupPoint())) {
                changed.add("pickupPoint");
            }
            if (!Objects.equals(current.transport().specialAssistance(), requested.specialAssistance())) {
                changed.add("specialAssistance");
            }
            if (changed.isEmpty()) {
                return current;  // same values sent again: no write, no event
            }

            long nextAdminVersion = current.adminVersion() + 1;
            employees.saveTransportState(tenantId, employeeId, requested, nextAdminVersion);
            outbox.append(tenantId, employeeId, ChangeType.TRANSPORT_UPDATED.name(), nextAdminVersion,
                    new OutboxPayload(employeeId, nextAdminVersion, current.status().name(),
                            current.hr().officeCode(), current.hr().shiftCode(), changed));

            return new Employee(current.tenantId(), current.employeeId(), current.status(),
                    current.hrVersion(), current.hr(), requested, nextAdminVersion);
        });
    }
}