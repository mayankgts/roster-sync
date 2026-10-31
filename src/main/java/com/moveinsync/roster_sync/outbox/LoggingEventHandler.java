package com.moveinsync.roster_sync.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in for a real broker: proves delivery in the logs. IDs and types only. */
@Component
public class LoggingEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventHandler.class);

    @Override
    public void handle(OutboxEvent event) {
        log.info("Published eventId={} type={} tenant={} employeeId={} version={}",
                event.id(), event.eventType(), event.tenantId(), event.employeeId(), event.sourceVersion());
    }
}