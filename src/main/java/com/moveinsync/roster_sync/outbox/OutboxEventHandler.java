package com.moveinsync.roster_sync.outbox;

/** A consumer of outbox events. Must be idempotent: the same event can arrive twice. */
public interface OutboxEventHandler {
    void handle(OutboxEvent event);
}