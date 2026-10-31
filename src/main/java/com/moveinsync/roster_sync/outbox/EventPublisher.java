package com.moveinsync.roster_sync.outbox;

/** Where events go. In production this would be Kafka/SQS; here it is in-process. */
public interface EventPublisher {
    void publish(OutboxEvent event);
}