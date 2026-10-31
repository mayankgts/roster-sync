package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.service.BatchResult;
import com.moveinsync.roster_sync.service.IngestService;
import com.moveinsync.roster_sync.service.RecordOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Run with: mvnw test -Dtest=IngestBenchmark
 * 8 concurrent clients each send 25 batches of 100 new employees (20,000 records).
 * Measured at the service layer: validation, lock, apply, upsert, outbox, commit.
 */
@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class IngestBenchmark {

    private static final int THREADS = 8;
    private static final int BATCHES_PER_THREAD = 25;
    private static final int BATCH_SIZE = 100;

    @Autowired IngestService ingestService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void ingestThroughputAndLatency() throws Exception {
        jdbc.execute("TRUNCATE employee, outbox RESTART IDENTITY CASCADE");
        ingestService.ingest("acme", batch("WARMUP", 0));  // JIT + connection pool warm-up, not measured

        List<Long> latenciesNs = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Integer>> clients = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            String client = "C" + t;
            clients.add(() -> {
                int applied = 0;
                for (int b = 0; b < BATCHES_PER_THREAD; b++) {
                    List<IncomingRecord> records = batch(client, b);
                    long start = System.nanoTime();
                    BatchResult result = ingestService.ingest("acme", records);
                    latenciesNs.add(System.nanoTime() - start);
                    applied += result.counts().get(RecordOutcome.APPLIED);
                }
                return applied;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long wallStart = System.nanoTime();
        int applied = 0;
        try {
            for (Future<Integer> f : pool.invokeAll(clients)) {
                applied += f.get();
            }
        } finally {
            pool.shutdown();
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1e9;

        List<Long> sorted = new ArrayList<>(latenciesNs);
        Collections.sort(sorted);
        int records = THREADS * BATCHES_PER_THREAD * BATCH_SIZE;
        Runtime rt = Runtime.getRuntime();

        System.out.printf("""

                ===== Ingest benchmark =====
                Machine     : %s %s, %d cores, Java %s
                Load        : %d clients x %d batches x %d records = %,d records
                Throughput  : %,.0f records/s  (wall %.2f s)
                Batch of %d : p50 %.1f ms   p95 %.1f ms   p99 %.1f ms   max %.1f ms
                Per record  : p50 %.2f ms (batch p50 / %d)
                ============================
                """,
                System.getProperty("os.name"), System.getProperty("os.arch"), rt.availableProcessors(),
                System.getProperty("java.version"),
                THREADS, BATCHES_PER_THREAD, BATCH_SIZE, records,
                records / wallSeconds, wallSeconds,
                BATCH_SIZE, ms(percentile(sorted, 50)), ms(percentile(sorted, 95)),
                ms(percentile(sorted, 99)), ms(sorted.get(sorted.size() - 1)),
                ms(percentile(sorted, 50)) / BATCH_SIZE, BATCH_SIZE);

        assertThat(applied).isEqualTo(records);
    }

    private static List<IncomingRecord> batch(String client, int batchNo) {
        List<IncomingRecord> records = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            String id = String.format("%s_%03d_%03d", client, batchNo, i);
            records.add(new IncomingRecord(id, "CREATE", 1L, "Test User", id.toLowerCase() + "@acme.com",
                    null, null, "BLR01", "DAY"));
        }
        return records;
    }

    /** Nearest-rank percentile on a sorted list. */
    private static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}