package com.moveinsync.roster_sync.outbox;

/**
 * One event as delivered to consumers. id is the outbox row id: consumers
 * use it as the dedup key, because delivery is at-least-once.
 */
public record OutboxEvent(
        long id,
        String tenantId,
        String employeeId,
        String eventType,
        long sourceVersion,
        String payload) {
}