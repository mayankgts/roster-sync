package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.FieldError;
import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.domain.TransportFields;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.service.BatchResult;
import com.moveinsync.roster_sync.service.IngestService;
import com.moveinsync.roster_sync.service.RecordOutcome;
import com.moveinsync.roster_sync.service.TransportService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IngestIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired TransportService transportService;
    @Autowired EmployeeRepository employees;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE employee, outbox RESTART IDENTITY").update();
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        List<IncomingRecord> feed = List.of(
                hrRecord("E1", "CREATE", 1, "BLR01", "DAY"),
                hrRecord("E2", "CREATE", 1, "HYD01", "NIGHT"),
                hrRecord("E1", "UPDATE", 2, "HYD01", "DAY"),
                hrRecord("E2", "DEACTIVATE", 2, null, null));

        ingestService.ingest("acme", feed);
        List<Map<String, Object>> afterFirst = employeeSnapshot();
        long outboxAfterFirst = outboxCount();

        BatchResult second = ingestService.ingest("acme", feed);

        assertThat(second.counts().get(RecordOutcome.APPLIED)).isZero();
        assertThat(employeeSnapshot()).isEqualTo(afterFirst);
        assertThat(outboxCount()).isEqualTo(outboxAfterFirst);
    }

    @Test
    void transferV7ArrivingBeforeShiftChangeV6Wins() {
        BatchResult result = ingestService.ingest("acme", List.of(
                hrRecord("E1", "UPDATE", 7, "HYD01", "NIGHT"),
                hrRecord("E1", "UPDATE", 6, "BLR01", "DAY")));

        assertThat(result.counts().get(RecordOutcome.STALE)).isEqualTo(1);
        Employee e = employees.find("acme", "E1").orElseThrow();
        assertThat(e.hrVersion()).isEqualTo(7);
        assertThat(e.hr().officeCode()).isEqualTo("HYD01");
        assertThat(e.hr().shiftCode()).isEqualTo("NIGHT");
    }

    @Test
    void oneBadRecordDoesNotFailTheBatch() {
        IncomingRecord bad = new IncomingRecord("E2", "CREATE", 1L, "Bad Data",
                "not-an-email", "123", null, "PUNE99", null);

        BatchResult result = ingestService.ingest("acme", List.of(
                hrRecord("E1", "CREATE", 1, "BLR01", "DAY"),
                bad,
                hrRecord("E3", "CREATE", 1, "HYD01", "DAY")));

        assertThat(result.counts().get(RecordOutcome.APPLIED)).isEqualTo(2);
        assertThat(result.counts().get(RecordOutcome.REJECTED)).isEqualTo(1);
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.index()).isEqualTo(1);
            assertThat(problem.errors()).extracting(FieldError::field)
                    .containsExactlyInAnyOrder("email", "phone", "officeCode");
        });
        assertThat(employees.find("acme", "E2")).isEmpty();
    }

    @Test
    void hrUpdateNeverOverwritesTransportOwnedFields() {
        ingestService.ingest("acme", List.of(hrRecord("E1", "CREATE", 1, "BLR01", "DAY")));
        transportService.update("acme", "E1", new TransportFields("Gate 3, Whitefield", "wheelchair"));

        ingestService.ingest("acme", List.of(hrRecord("E1", "UPDATE", 2, "HYD01", "DAY")));

        Employee e = employees.find("acme", "E1").orElseThrow();
        assertThat(e.hr().officeCode()).isEqualTo("HYD01");
        assertThat(e.transport()).isEqualTo(new TransportFields("Gate 3, Whitefield", "wheelchair"));
        assertThat(e.hrVersion()).isEqualTo(2);
        assertThat(e.adminVersion()).isEqualTo(1);
    }

    @Test
    void sameEmployeeIdInAnotherTenantIsIndependent() {
        ingestService.ingest("acme", List.of(hrRecord("E1", "CREATE", 5, "BLR01", "DAY")));
        ingestService.ingest("globex", List.of(hrRecord("E1", "CREATE", 1, "BLR01", "NIGHT")));

        assertThat(employees.find("acme", "E1").orElseThrow().hrVersion()).isEqualTo(5);
        assertThat(employees.find("globex", "E1").orElseThrow().hrVersion()).isEqualTo(1);
    }

    @Test
    void concurrentUpdatesForOneEmployeeEndAtHighestVersion() throws Exception {
        int versions = 200;
        int threads = 8;
        List<Long> shuffled = new ArrayList<>();
        for (long v = 1; v <= versions; v++) {
            shuffled.add(v);
        }
        Collections.shuffle(shuffled, new Random(42));

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<Long> slice = new ArrayList<>();
            for (int i = t; i < versions; i += threads) {
                slice.add(shuffled.get(i));
            }
            tasks.add(() -> {
                int applied = 0;
                for (long v : slice) {
                    String office = v % 2 == 0 ? "BLR01" : "HYD01";
                    BatchResult r = ingestService.ingest("acme",
                            List.of(hrRecord("E1", "UPDATE", v, office, "DAY")));
                    assertThat(r.counts().get(RecordOutcome.FAILED)).isZero();
                    applied += r.counts().get(RecordOutcome.APPLIED);
                }
                return applied;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int totalApplied = 0;
        try {
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                totalApplied += future.get();
            }
        } finally {
            pool.shutdown();
        }

        Employee e = employees.find("acme", "E1").orElseThrow();
        assertThat(e.hrVersion()).isEqualTo(versions);
        assertThat(e.hr().officeCode()).isEqualTo("BLR01");  // v200 is even
        assertThat(outboxCount()).isEqualTo(totalApplied);   // exactly one event per APPLIED
    }

    // ---- helpers ----

    private static IncomingRecord hrRecord(String id, String type, long version, String office, String shift) {
        return new IncomingRecord(id, type, version, "Test User", id.toLowerCase() + "@acme.com",
                null, null, office, shift);
    }

    private List<Map<String, Object>> employeeSnapshot() {
        return jdbc.sql("""
                SELECT tenant_id, employee_id, status, hr_version, name, email,
                       office_code, shift_code, pickup_point, admin_version
                  FROM employee ORDER BY tenant_id, employee_id
                """).query().listOfRows();
    }

    private long outboxCount() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }
}