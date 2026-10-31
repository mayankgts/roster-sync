package com.moveinsync.roster_sync.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the relay on a timer. Disabled in tests so tests drive the relay by hand. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "roster.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${roster.outbox.poll-interval-ms:1000}")
    public void poll() {
        relay.publishPending();
    }
}