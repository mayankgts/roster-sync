package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.outbox.OutboxRelay;
import com.moveinsync.roster_sync.service.IngestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability  // tests disable metric export by default; this turns Prometheus on
@Import(TestcontainersConfiguration.class)
class MetricsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IngestService ingestService;
    @Autowired OutboxRelay relay;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE employee, outbox RESTART IDENTITY CASCADE").update();
    }

    @Test
    void ingestOutcomesAreCountedPerTenant() {
        double appliedBefore = ingestCounter("APPLIED");
        double rejectedBefore = ingestCounter("REJECTED");

        ingestService.ingest("acme", List.of(
                employee("E1"),
                new IncomingRecord("E2", "CREATE", 1L, "Bad", "not-an-email", null, null, "BLR01", null)));

        assertThat(ingestCounter("APPLIED") - appliedBefore).isEqualTo(1.0);
        assertThat(ingestCounter("REJECTED") - rejectedBefore).isEqualTo(1.0);
    }

    @Test
    void queueDepthRisesWithChangesAndDrainsAfterPublish() {
        ingestService.ingest("acme", List.of(employee("E1"), employee("E2")));

        assertThat(gauge("roster.outbox.pending")).isEqualTo(2.0);
        assertThat(gauge("roster.outbox.lag")).isGreaterThanOrEqualTo(0.0);

        relay.publishPending();

        assertThat(gauge("roster.outbox.pending")).isZero();
        assertThat(gauge("roster.outbox.lag")).isZero();
    }

    @Test
    void prometheusEndpointExposesOnCallMetrics() throws Exception {
        ingestService.ingest("acme", List.of(employee("E1")));

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("roster_ingest_records_total")))
                .andExpect(content().string(containsString("roster_outbox_pending")))
                .andExpect(content().string(containsString("roster_outbox_lag_seconds")))
                .andExpect(content().string(containsString("roster_outbox_dead_lettered")));
    }

    private double ingestCounter(String outcome) {
        Counter counter = registry.find("roster.ingest.records")
                .tags("tenant", "acme", "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static IncomingRecord employee(String id) {
        return new IncomingRecord(id, "CREATE", 1L, "Test User", id.toLowerCase() + "@acme.com",
                null, null, "BLR01", "DAY");
    }
}