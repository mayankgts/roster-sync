package com.moveinsync.roster_sync.reconciliation;

import com.moveinsync.roster_sync.domain.ApplyOutcome;
import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.EmployeeEvent;
import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.domain.RecordHasher;
import com.moveinsync.roster_sync.domain.RecordValidator;
import com.moveinsync.roster_sync.domain.ValidationResult;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.persistence.OfficeRepository;
import com.moveinsync.roster_sync.persistence.OutboxRepository;
import com.moveinsync.roster_sync.service.ChangeType;
import com.moveinsync.roster_sync.service.IngestService;
import com.moveinsync.roster_sync.service.InvalidStateException;
import com.moveinsync.roster_sync.service.NotFoundException;
import com.moveinsync.roster_sync.service.OutboxPayload;
import com.moveinsync.roster_sync.service.RecordResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {

    static final int BUCKETS = 16_384;
    private static final int MAX_REPORTED_PROBLEMS = 100;
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final RecordValidator validator;
    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final ReconciliationRepository runs;
    private final IngestService ingestService;
    private final TransactionTemplate tx;
    private final double brakeThreshold;
    private final Path storageDir;

    public ReconciliationService(RecordValidator validator, OfficeRepository offices, EmployeeRepository employees,
                                 OutboxRepository outbox, ReconciliationRepository runs, IngestService ingestService,
                                 PlatformTransactionManager txManager,
                                 @Value("${roster.reconciliation.brake-threshold:0.30}") double brakeThreshold,
                                 @Value("${roster.reconciliation.storage-dir:${java.io.tmpdir}/roster-reconciliation}")
                                 String storageDir) {
        this.validator = validator;
        this.offices = offices;
        this.employees = employees;
        this.outbox = outbox;
        this.runs = runs;
        this.ingestService = ingestService;
        this.tx = new TransactionTemplate(txManager);
        this.brakeThreshold = brakeThreshold;
        this.storageDir = Path.of(storageDir);
    }

    /** Stores the upload (so an approval can re-run it later) and reconciles it. */
    public ReconciliationReport start(String tenantId, InputStream upload) throws IOException {
        Files.createDirectories(storageDir);
        Path file = storageDir.resolve(UUID.randomUUID() + ".csv");  // never use client input in paths
        Files.copy(upload, file);
        long runId = runs.createRun(tenantId, file.toString());
        return execute(runId, tenantId, file, false);
    }

    /** A human confirms a BLOCKED run is genuine. Re-runs the diff with the brake released. */
    public ReconciliationReport approve(String tenantId, long runId, String approvedBy) throws IOException {
        ReconciliationRun run = find(tenantId, runId);
        if (!runs.markApproved(tenantId, runId, approvedBy)) {
            throw new InvalidStateException("Only a BLOCKED run can be approved");
        }
        log.warn("Safety brake released tenant={} runId={} approvedBy={}", tenantId, runId, approvedBy);
        return execute(runId, tenantId, Path.of(run.filePath()), true);
    }

    public ReconciliationRun find(String tenantId, long runId) {
        return runs.find(tenantId, runId)
                .orElseThrow(() -> new NotFoundException("Reconciliation run not found"));
    }

    private ReconciliationReport execute(long runId, String tenantId, Path file, boolean approved)
            throws IOException {
        long startedAt = System.nanoTime();
        Set<String> officeCodes = offices.findCodes(tenantId);
        Tally t = new Tally();
        List<RecordResult> problems = new ArrayList<>();

        try {
            // ---- Pass 1: file fingerprints per bucket; report bad rows ----
            long[] fileDigest = new long[BUCKETS];
            try (CsvRowReader reader = new CsvRowReader(file)) {
                IncomingRecord record;
                while ((record = reader.next()) != null) {
                    t.fileRows++;
                    ValidationResult v = validator.validate(tenantId, record, officeCodes);
                    if (v.isValid()) {
                        EmployeeEvent e = v.event();
                        fileDigest[bucketOf(e.employeeId())] += hashOf(e);
                    } else {
                        t.rejected++;
                        if (problems.size() < MAX_REPORTED_PROBLEMS) {
                            problems.add(RecordResult.rejected(reader.lineNumber(), record.employeeId(), v.errors()));
                        }
                    }
                }
            }

            // ---- Pass A: database fingerprints per bucket ----
            long[] dbDigest = new long[BUCKETS];
            boolean[] dirty = new boolean[BUCKETS];
            runs.forEachActiveHash(tenantId, (employeeId, hash) -> {
                t.activeBefore++;
                int bucket = bucketOf(employeeId);
                if (hash == null) {
                    dirty[bucket] = true;  // legacy row without a hash: must be inspected
                } else {
                    dbDigest[bucket] += hash;
                }
            });
            for (int b = 0; b < BUCKETS; b++) {
                if (fileDigest[b] != dbDigest[b]) {
                    dirty[b] = true;
                }
                if (dirty[b]) {
                    t.dirtyBuckets++;
                }
            }

            // ---- Pass B: load hashes only for employees in dirty buckets ----
            Map<String, Long> dbHashes = new HashMap<>();
            runs.forEachActiveHash(tenantId, (employeeId, hash) -> {
                if (dirty[bucketOf(employeeId)]) {
                    dbHashes.put(employeeId, hash);
                }
            });

            // ---- Pass 2: presence and change count, before touching anything ----
            Set<String> present = new HashSet<>();
            try (CsvRowReader reader = new CsvRowReader(file)) {
                IncomingRecord record;
                while ((record = reader.next()) != null) {
                    String id = presenceKey(record);
                    if (id == null || !dirty[bucketOf(id)]) {
                        continue;
                    }
                    present.add(id);  // even an invalid row proves the employee still exists
                    ValidationResult v = validator.validate(tenantId, record, officeCodes);
                    if (v.isValid() && !Objects.equals(dbHashes.get(id), hashOf(v.event()))) {
                        t.changed++;
                    }
                }
            }
            List<String> missing = new ArrayList<>();
            for (String id : dbHashes.keySet()) {
                if (!present.contains(id)) {
                    missing.add(id);
                }
            }
            t.missing = missing.size();
            double ratio = t.activeBefore == 0 ? 0 : (double) t.missing / t.activeBefore;

            // ---- Safety brake ----
            if (!approved && t.missing > 0 && ratio >= brakeThreshold) {
                log.warn("SAFETY BRAKE tenant={} runId={} active={} missing={} ratio={} threshold={}",
                        tenantId, runId, t.activeBefore, t.missing, ratio, brakeThreshold);
                return finish(tenantId, runId, "BLOCKED", t, ratio, startedAt, problems);
            }

            // ---- Pass 3: apply changed rows through the normal, version-checked path ----
            try (CsvRowReader reader = new CsvRowReader(file)) {
                IncomingRecord record;
                while ((record = reader.next()) != null) {
                    String id = presenceKey(record);
                    if (id == null || !dirty[bucketOf(id)]) {
                        continue;
                    }
                    ValidationResult v = validator.validate(tenantId, record, officeCodes);
                    if (!v.isValid() || Objects.equals(dbHashes.get(id), hashOf(v.event()))) {
                        continue;
                    }
                    try {
                        t.count(ingestService.applyValidated(v.event()));
                    } catch (RuntimeException e) {
                        t.failed++;
                        log.error("Reconciliation apply failed runId={} employeeId={} error={}",
                                runId, id, e.getClass().getSimpleName());
                    }
                }
            }

            // ---- Deactivate employees missing from the export ----
            for (String id : missing) {
                try {
                    if (deactivateMissing(tenantId, id, dbHashes.get(id))) {
                        t.deactivated++;
                    } else {
                        t.skippedConcurrentChange++;
                    }
                } catch (RuntimeException e) {
                    t.failed++;
                    log.error("Reconciliation deactivate failed runId={} employeeId={} error={}",
                            runId, id, e.getClass().getSimpleName());
                }
            }

            return finish(tenantId, runId, "COMPLETED", t, ratio, startedAt, problems);
        } catch (IOException | RuntimeException e) {
            runs.finish(runId, "FAILED", t.toSummary(0, brakeThreshold, elapsedMs(startedAt)));
            throw e;
        }
    }

    private boolean deactivateMissing(String tenantId, String employeeId, Long expectedHash) {
        Boolean done = tx.execute(status -> {
            employees.lock(tenantId, employeeId);
            if (employees.deactivateIfUnchanged(tenantId, employeeId, expectedHash) == 0) {
                return false;  // HR changed this employee after the diff: leave it alone
            }
            Employee after = employees.find(tenantId, employeeId).orElseThrow();
            outbox.append(tenantId, employeeId, ChangeType.EMPLOYEE_DEACTIVATED.name(), after.hrVersion(),
                    new OutboxPayload(employeeId, after.hrVersion(), after.status().name(),
                            after.hr().officeCode(), after.hr().shiftCode(), Set.of("status")));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private ReconciliationReport finish(String tenantId, long runId, String status, Tally t, double ratio,
                                        long startedAt, List<RecordResult> problems) {
        runs.finish(runId, status, t.toSummary(ratio, brakeThreshold, elapsedMs(startedAt)));
        log.info("Reconciliation tenant={} runId={} status={} rows={} dirtyBuckets={} changed={} missing={}",
                tenantId, runId, status, t.fileRows, t.dirtyBuckets, t.changed, t.missing);
        return new ReconciliationReport(find(tenantId, runId), problems);
    }

    private static String presenceKey(IncomingRecord record) {
        if (record.employeeId() == null || record.employeeId().isBlank()) {
            return null;
        }
        return record.employeeId().strip();
    }

    private static int bucketOf(String employeeId) {
        return RecordHasher.bucket(employeeId, BUCKETS);
    }

    private static long hashOf(EmployeeEvent e) {
        return RecordHasher.hash(e.employeeId(), e.version(), e.hr());
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** Mutable counters for one run. */
    private static final class Tally {
        int fileRows, rejected, activeBefore, dirtyBuckets, changed, missing;
        int applied, noChange, staleOrDuplicate, deactivated, skippedConcurrentChange, failed;

        void count(ApplyOutcome outcome) {
            switch (outcome) {
                case APPLIED -> applied++;
                case NO_CHANGE -> noChange++;
                case DUPLICATE, STALE -> staleOrDuplicate++;
            }
        }

        ReconciliationSummary toSummary(double ratio, double threshold, long durationMs) {
            return new ReconciliationSummary(fileRows, rejected, activeBefore, dirtyBuckets, changed, missing,
                    ratio, threshold, applied, noChange, staleOrDuplicate, deactivated,
                    skippedConcurrentChange, failed, durationMs);
        }
    }
}