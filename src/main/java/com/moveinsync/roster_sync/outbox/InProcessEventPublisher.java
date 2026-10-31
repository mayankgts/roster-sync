package com.moveinsync.roster_sync.outbox;

import java.util.List;
import org.springframework.stereotype.Component;

/** Delivers each event to every registered handler, in order. Any failure fails the publish. */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private final List<OutboxEventHandler> handlers;

    public InProcessEventPublisher(List<OutboxEventHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void publish(OutboxEvent event) {
        for (OutboxEventHandler handler : handlers) {
            handler.handle(event);
        }
    }
}