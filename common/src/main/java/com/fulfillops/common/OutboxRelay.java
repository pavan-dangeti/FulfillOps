package com.fulfillops.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox rows to the broker on a timer. Kept separate from {@link Outbox} so that the
 * table stays usable — a service can record events in tests with no broker running — while the
 * parts that need a broker are switched off together.
 */
@Component
@ConditionalOnProperty(name = "fulfillops.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final Outbox outbox;

    public OutboxRelay(Outbox outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${fulfillops.messaging.relay-delay-ms:200}")
    public void publish() {
        outbox.drain();
    }
}
