package com.moveinsync.roster_sync.service;

import com.moveinsync.roster_sync.domain.ApplyOutcome;
import com.moveinsync.roster_sync.domain.ApplyResult;
import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.EmployeeEvent;
import com.moveinsync.roster_sync.domain.EventApplier;
import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.domain.RecordValidator;
import com.moveinsync.roster_sync.domain.ValidationResult;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.persistence.OfficeRepository;
import com.moveinsync.roster_sync.persistence.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final RecordValidator validator;
    private final EventApplier applier;
    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;

    public IngestService(RecordValidator validator, EventApplier applier, OfficeRepository offices,
                         EmployeeRepository employees, OutboxRepository outbox,
                         PlatformTransactionManager txManager) {
        this.validator = validator;
        this.applier = applier;
        this.offices = offices;
        this.employees = employees;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult ingest(String tenantId, List<IncomingRecord> records) {
        Set<String> officeCodes = offices.findCodes(tenantId);
        List<RecordResult> results = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            results.add(ingestOne(tenantId, i, records.get(i), officeCodes));
        }
        BatchResult batch = BatchResult.of(results);
        log.info("Batch tenant={} total={} counts={}", tenantId, batch.total(), batch.counts());
        return batch;
    }

    private RecordResult ingestOne(String tenantId, int index, IncomingRecord record, Set<String> officeCodes) {
        ValidationResult validation = validator.validate(tenantId, record, officeCodes);
        if (!validation.isValid()) {
            return RecordResult.rejected(index, record.employeeId(), validation.errors());
        }

        EmployeeEvent event = validation.event();
        try {
            ApplyOutcome outcome = tx.execute(status -> applyInTransaction(event));
            return RecordResult.of(index, event.employeeId(), outcome);
        } catch (RuntimeException e) {
            // Log the exception TYPE only. Database error messages can contain the
            // failing row's values (name, email, phone), which must never reach logs.
            log.error("Apply failed tenant={} employeeId={} version={} error={}",
                    tenantId, event.employeeId(), event.version(), e.getClass().getSimpleName());
            return RecordResult.failed(index, event.employeeId());
        }
    }

    /** Runs inside one transaction: lock, read, decide, write state + outbox together. */
    private ApplyOutcome applyInTransaction(EmployeeEvent event) {
        employees.lock(event.tenantId(), event.employeeId());
        Employee current = employees.find(event.tenantId(), event.employeeId()).orElse(null);

        ApplyResult result = applier.apply(current, event);

        if (result.needsSave()) {
            employees.saveHrState(result.employee());
        }
        if (result.needsEvent()) {
            Employee after = result.employee();
            OutboxPayload payload = new OutboxPayload(
                    after.employeeId(), after.hrVersion(), after.status().name(),
                    after.hr().officeCode(), after.hr().shiftCode(), result.changedFields());
            outbox.append(after.tenantId(), after.employeeId(),
                    ChangeType.of(current, after).name(), after.hrVersion(), payload);
        }
        return result.outcome();
    }
        /** Applies one already-validated event in its own transaction. Used by reconciliation. */
    public ApplyOutcome applyValidated(EmployeeEvent event) {
        return tx.execute(status -> applyInTransaction(event));
    }
}