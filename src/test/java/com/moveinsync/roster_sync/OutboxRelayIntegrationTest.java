package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.outbox.OutboxEvent;
import com.moveinsync.roster_sync.outbox.OutboxEventHandler;
import com.moveinsync.roster_sync.outbox.OutboxRelay;
import com.moveinsync.roster_sync.persistence.OutboxRepository;
import com.moveinsync.roster_sync.service.BatchResult;
import com.moveinsync.roster_sync.service.IngestService;
import com.moveinsync.roster_sync.service.RecordOutcome;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@Import({TestcontainersConfiguration.class, OutboxRelayIntegrationTest.RecordingConfig.class})
class OutboxRelayIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired OutboxRelay relay;
    @Autowired RecordingHandler handler;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean OutboxRepository outboxRepository;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE employee, outbox RESTART IDENTITY CASCADE").update();
        handler.reset();
    }

    /** The case's crash test: process dies after the DB commit, before anything is published. */
    @Test
    void crashAfterCommitBeforePublish_eventIsDeliveredExactlyOnceAfterRestart() {
        ingest(hrRecord("E1", "CREATE", 1));

        // "Crash" here: the relay never ran. The change is committed and its event is waiting.
        assertThat(handler.received()).isEmpty();
        assertThat(pendingCount()).isEqualTo(1);

        // "Restart": the relay runs again and finds the pending event.
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(relay.publishPending()).isZero();

        assertThat(handler.received()).singleElement().satisfies(event -> {
            assertThat(event.employeeId()).isEqualTo("E1");
            assertThat(event.eventType()).isEqualTo("EMPLOYEE_CREATED");
        });
    }

    /** Failure inside the transaction, after the employee write: nothing may survive. */
    @Test
    void crashBetweenEmployeeWriteAndOutboxWrite_rollsBackBoth() {
        doThrow(new IllegalStateException("simulated crash"))
                .when(outboxRepository).append(any(), any(), any(), anyLong(), any());

        BatchResult result = ingest(hrRecord("E1", "CREATE", 1));

        assertThat(result.counts().get(RecordOutcome.FAILED)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM employee")).isZero();
        assertThat(count("SELECT count(*) FROM outbox")).isZero();
    }

    @Test
    void failedPublishIsRetriedOnNextRun() {
        handler.failNext(1);
        ingest(hrRecord("E1", "CREATE", 1));

        assertThat(relay.publishPending()).isZero();
        assertThat(count("SELECT attempts FROM outbox WHERE id = 1")).isEqualTo(1);

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(handler.received()).hasSize(1);
    }

    @Test
    void eventKeepsFailingIsDeadLetteredAfterMaxAttempts() {
        handler.failNext(100);
        ingest(hrRecord("E1", "CREATE", 1));

        for (int i = 0; i < 5; i++) {
            relay.publishPending();
        }

        assertThat(pendingCount()).isZero();
        assertThat(count("SELECT count(*) FROM outbox WHERE dead_lettered_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void eventsForOneEmployeeArePublishedInOrder() {
        ingest(hrRecord("E1", "CREATE", 1));
        ingest(new IncomingRecord("E1", "UPDATE", 2L, "Test User", "e1@acme.com", null, null, "HYD01", "DAY"));
        ingest(hrRecord("E1", "DEACTIVATE", 3));

        relay.publishPending();

        assertThat(handler.received()).extracting(OutboxEvent::eventType)
                .containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED", "EMPLOYEE_DEACTIVATED");
    }

    // ---- helpers ----

    private BatchResult ingest(IncomingRecord record) {
        return ingestService.ingest("acme", List.of(record));
    }

    private static IncomingRecord hrRecord(String id, String type, long version) {
        return new IncomingRecord(id, type, version, "Test User", id.toLowerCase() + "@acme.com",
                null, null, "BLR01", "DAY");
    }

    private long pendingCount() {
        return count("SELECT count(*) FROM outbox WHERE published_at IS NULL AND dead_lettered_at IS NULL");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** A fake consumer that records what it receives and can be told to fail. */
    static class RecordingHandler implements OutboxEventHandler {
        private final List<OutboxEvent> received = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void handle(OutboxEvent event) {
            if (failuresLeft.get() > 0) {
                failuresLeft.decrementAndGet();
                throw new IllegalStateException("broker unavailable");
            }
            received.add(event);
        }

        List<OutboxEvent> received() {
            return received;
        }

        void failNext(int times) {
            failuresLeft.set(times);
        }

        void reset() {
            received.clear();
            failuresLeft.set(0);
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }
}