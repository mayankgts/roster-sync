package com.moveinsync.roster_sync.outbox;

import com.moveinsync.roster_sync.persistence.OutboxRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final TransactionTemplate perEvent;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, PlatformTransactionManager txManager,
                       @Value("${roster.outbox.batch-size:100}") int batchSize,
                       @Value("${roster.outbox.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
        // Savepoint per event: a failing handler rolls back only its own work,
        // and the outer batch transaction stays usable to record the failure.
        this.perEvent = new TransactionTemplate(txManager);
        this.perEvent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Publishes one batch of pending events. Returns how many were published. */
    public int publishPending() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.claimPending(batchSize);
            Set<String> blockedEmployees = new HashSet<>();
            int count = 0;

            for (OutboxEvent event : batch) {
                String employeeKey = event.tenantId() + ":" + event.employeeId();
                if (blockedEmployees.contains(employeeKey)) {
                    continue;  // an earlier event for this employee failed: keep their order
                }
                try {
                    perEvent.executeWithoutResult(s -> {
                        publisher.publish(event);
                        outbox.markPublished(event.id());
                    });
                    count++;
                } catch (RuntimeException e) {
                    outbox.recordFailure(event.id(), e.getClass().getSimpleName(), maxAttempts);
                    blockedEmployees.add(employeeKey);
                    log.warn("Publish failed eventId={} type={} employeeId={} error={}",
                            event.id(), event.eventType(), event.employeeId(), e.getClass().getSimpleName());
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }
}