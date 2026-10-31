package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.reconciliation.ReconciliationReport;
import com.moveinsync.roster_sync.reconciliation.ReconciliationService;
import com.moveinsync.roster_sync.reconciliation.ReconciliationSummary;
import com.moveinsync.roster_sync.service.IngestService;
import com.moveinsync.roster_sync.service.InvalidStateException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class ReconciliationIntegrationTest {

    private static final String HEADER = "employee_id,version,name,email,phone,manager_id,office_code,shift_code";

    @Autowired ReconciliationService reconciliation;
    @Autowired IngestService ingestService;
    @Autowired EmployeeRepository employees;
    @Autowired JdbcClient jdbc;
    @TempDir Path tempDir;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE employee, outbox, reconciliation_run RESTART IDENTITY CASCADE").update();
    }

    @Test
    void unchangedFileTouchesNothing() throws IOException {
        seed(1, 50);
        long outboxBefore = outboxCount();

        ReconciliationReport report = run(rows(1, 50));

        ReconciliationSummary s = report.run().summary();
        assertThat(report.run().status()).isEqualTo("COMPLETED");
        assertThat(s.dirtyBuckets()).isZero();
        assertThat(s.applied()).isZero();
        assertThat(outboxCount()).isEqualTo(outboxBefore);
    }

    @Test
    void appliesChangesAddsJoinersAndDeactivatesLeavers() throws IOException {
        seed(1, 20);
        List<String> rows = new ArrayList<>(rows(1, 18));  // E019, E020 left (10%)
        rows.set(0, row("E001", 2, "HYD01"));               // transfer
        rows.add(row("E021", 1, "BLR01"));                  // new joiner

        ReconciliationReport report = run(rows);

        ReconciliationSummary s = report.run().summary();
        assertThat(report.run().status()).isEqualTo("COMPLETED");
        assertThat(s.missing()).isEqualTo(2);
        assertThat(s.applied()).isEqualTo(2);
        assertThat(s.deactivated()).isEqualTo(2);
        assertThat(employees.find("acme", "E001").orElseThrow().hr().officeCode()).isEqualTo("HYD01");
        assertThat(employees.find("acme", "E021")).isPresent();
        assertThat(employees.find("acme", "E019").orElseThrow().isActive()).isFalse();
        assertThat(employees.find("acme", "E018").orElseThrow().isActive()).isTrue();
    }

    @Test
    void truncatedFileTriggersSafetyBrakeAndChangesNothing() throws IOException {
        seed(1, 10);
        List<String> rows = new ArrayList<>(rows(1, 6));  // 4 of 10 missing = 40%
        rows.set(0, row("E001", 2, "HYD01"));

        ReconciliationReport report = run(rows);

        assertThat(report.run().status()).isEqualTo("BLOCKED");
        assertThat(report.run().summary().missing()).isEqualTo(4);
        assertThat(activeCount()).isEqualTo(10);
        assertThat(employees.find("acme", "E001").orElseThrow().hr().officeCode()).isEqualTo("BLR01");
    }

    @Test
    void approvalResumesABlockedRunExactlyOnce() throws IOException {
        seed(1, 10);
        long runId = run(rows(1, 6)).run().id();

        ReconciliationReport approved = reconciliation.approve("acme", runId, "ops-anita");

        assertThat(approved.run().status()).isEqualTo("COMPLETED");
        assertThat(approved.run().approvedBy()).isEqualTo("ops-anita");
        assertThat(approved.run().summary().deactivated()).isEqualTo(4);
        assertThat(activeCount()).isEqualTo(6);
        assertThatThrownBy(() -> reconciliation.approve("acme", runId, "ops-anita"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void badRowIsReportedAndThatEmployeeIsNotDeactivated() throws IOException {
        seed(1, 10);
        List<String> rows = new ArrayList<>(rows(1, 10));
        rows.set(4, "E005,2,Test User,not-an-email,,,BLR01,DAY");

        ReconciliationReport report = run(rows);

        assertThat(report.run().summary().rejectedRows()).isEqualTo(1);
        assertThat(report.problems()).singleElement().satisfies(p -> {
            assertThat(p.employeeId()).isEqualTo("E005");
            assertThat(p.index()).isEqualTo(6);  // line 6: header is line 1
        });
        assertThat(report.run().summary().deactivated()).isZero();
        assertThat(employees.find("acme", "E005").orElseThrow().isActive()).isTrue();
    }

    // ---- helpers ----

    private void seed(int from, int to) {
        List<IncomingRecord> records = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            String id = id(i);
            records.add(new IncomingRecord(id, "CREATE", 1L, "Test User", id.toLowerCase() + "@acme.com",
                    null, null, "BLR01", "DAY"));
        }
        ingestService.ingest("acme", records);
    }

    private static List<String> rows(int from, int to) {
        List<String> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(row(id(i), 1, "BLR01"));
        }
        return rows;
    }

    private static String row(String id, long version, String office) {
        return id + "," + version + ",Test User," + id.toLowerCase() + "@acme.com,,," + office + ",DAY";
    }

    private static String id(int i) {
        return String.format("E%03d", i);
    }

    private ReconciliationReport run(List<String> rows) throws IOException {
        Path file = tempDir.resolve("export-" + System.nanoTime() + ".csv");
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.addAll(rows);
        Files.write(file, lines);
        try (InputStream in = Files.newInputStream(file)) {
            return reconciliation.start("acme", in);
        }
    }

    private long activeCount() {
        return jdbc.sql("SELECT count(*) FROM employee WHERE tenant_id = 'acme' AND status = 'ACTIVE'")
                .query(Long.class).single();
    }

    private long outboxCount() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }
}