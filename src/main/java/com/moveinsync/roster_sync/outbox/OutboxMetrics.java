package com.moveinsync.roster_sync.outbox;

import com.moveinsync.roster_sync.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/**
 * Gauges are evaluated when Prometheus scrapes (every ~15s), not on every event,
 * so the cost is three small indexed queries per scrape.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
        Gauge.builder("roster.outbox.pending", outbox, o -> safe(o::countPending))
                .description("Events waiting to be published (queue depth)")
                .register(registry);
        Gauge.builder("roster.outbox.lag", outbox, o -> safe(o::oldestPendingAgeSeconds))
                .description("Age of the oldest unpublished event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("roster.outbox.dead.lettered", outbox, o -> safe(o::countDeadLettered))
                .description("Events that exhausted their retries and need an operator")
                .register(registry);
    }

    /** If the database is down, report NaN ("unknown") instead of failing the whole scrape. */
    private static double safe(DoubleSupplier query) {
        try {
            return query.getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}