package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.roster_sync.domain.HrFields;
import com.moveinsync.roster_sync.domain.RecordHasher;
import com.moveinsync.roster_sync.reconciliation.ReconciliationReport;
import com.moveinsync.roster_sync.reconciliation.ReconciliationService;
import com.moveinsync.roster_sync.reconciliation.ReconciliationSummary;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Run with: mvnw test -Dtest=ReconciliationBenchmark -DargLine=-Xmx256m
 * The heap cap is the proof of "bounded memory": an implementation that loaded
 * both sides fully would fail with OutOfMemoryError instead of passing.
 */
@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class ReconciliationBenchmark {

    private static final int SEEDED = 200_000;
    private static final int JOINERS = 500;
    private static final String HEADER = "employee_id,version,name,email,phone,manager_id,office_code,shift_code";

    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path tempDir;

    @Test
    void reconcile200kRowsUnderSixtySeconds() throws Exception {
        jdbc.execute("TRUNCATE employee, outbox, reconciliation_run RESTART IDENTITY CASCADE");

        long seedStart = System.nanoTime();
        seedActiveEmployees();
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000;

        Path file = writeNightlyExport();

        System.gc();
        long baselineMb = usedHeapMb();
        ReconciliationReport report;
        long wallMs;
        long peakMb;
        try (HeapSampler sampler = new HeapSampler();
             InputStream in = Files.newInputStream(file)) {
            long start = System.nanoTime();
            report = reconciliation.start("acme", in);
            wallMs = (System.nanoTime() - start) / 1_000_000;
            peakMb = sampler.peakMb();
        }

        ReconciliationSummary s = report.run().summary();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("""

                ===== Reconciliation benchmark =====
                Machine        : %s %s, %d cores, Java %s, max heap %d MB
                Seeded         : %,d active employees (%,d ms, not part of the measurement)
                File rows      : %,d   (%,d transfers, %,d leavers, %,d joiners)
                Dirty buckets  : %,d of 16,384
                Changed rows   : %,d   applied=%,d   deactivated=%,d
                Wall time      : %,d ms   (limit 60,000 ms)
                Heap           : baseline %d MB, peak %d MB
                ====================================
                """,
                System.getProperty("os.name"), System.getProperty("os.arch"), rt.availableProcessors(),
                System.getProperty("java.version"), rt.maxMemory() / (1024 * 1024),
                SEEDED, seedMs, s.fileRows(), SEEDED / 100, SEEDED / 200, JOINERS,
                s.dirtyBuckets(), s.changedRows(), s.applied(), s.deactivated(),
                wallMs, baselineMb, peakMb);

        assertThat(report.run().status()).isEqualTo("COMPLETED");
        assertThat(s.changedRows()).isEqualTo(SEEDED / 100 + JOINERS);
        assertThat(s.missing()).isEqualTo(SEEDED / 200);
        assertThat(s.deactivated()).isEqualTo(SEEDED / 200);
        assertThat(wallMs).isLessThan(60_000);
    }

    /** Fast bulk seed straight into the table, with the same hash the app would compute. */
    private void seedActiveEmployees() {
        String sql = """
                INSERT INTO employee (tenant_id, employee_id, status, hr_version, name, email,
                                      office_code, shift_code, record_hash)
                VALUES ('acme', ?, 'ACTIVE', 1, ?, ?, 'BLR01', 'DAY', ?)
                """;
        List<Object[]> batch = new ArrayList<>(5_000);
        for (int i = 1; i <= SEEDED; i++) {
            String id = id(i);
            HrFields hr = hr(id, "BLR01");
            batch.add(new Object[] {id, hr.name(), hr.email(), RecordHasher.hash(id, 1, hr)});
            if (batch.size() == 5_000) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(sql, batch);
        }
    }

    /**
     * A realistic night: 1% transfers (new version, new office), 0.5% leavers
     * (absent from the file, well under the 30% brake), plus new joiners.
     */
    private Path writeNightlyExport() throws Exception {
        Path file = tempDir.resolve("export-200k.csv");
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(HEADER);
            w.newLine();
            for (int i = 1; i <= SEEDED; i++) {
                if (i % 200 == 0) {
                    continue;  // leaver
                }
                boolean transfer = i % 100 == 1;
                w.write(row(id(i), transfer ? 2 : 1, transfer ? "HYD01" : "BLR01"));
                w.newLine();
            }
            for (int j = 1; j <= JOINERS; j++) {
                w.write(row(id(SEEDED + j), 1, "BLR01"));
                w.newLine();
            }
        }
        return file;
    }

    private static String row(String id, long version, String office) {
        HrFields hr = hr(id, office);
        return id + "," + version + "," + hr.name() + "," + hr.email() + ",,," + office + ",DAY";
    }

    private static HrFields hr(String id, String office) {
        return new HrFields("Test User", id.toLowerCase() + "@acme.com", null, null, office, "DAY");
    }

    private static String id(int i) {
        return String.format("E%06d", i);
    }

    private static long usedHeapMb() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    /** Samples heap usage every 20 ms on a background thread and remembers the peak. */
    private static final class HeapSampler implements AutoCloseable {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final AtomicLong peak = new AtomicLong();
        private final Thread thread;

        HeapSampler() {
            thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        long peakMb() {
            return peak.get() / (1024 * 1024);
        }

        @Override
        public void close() throws InterruptedException {
            thread.interrupt();
            thread.join();
        }
    }
}