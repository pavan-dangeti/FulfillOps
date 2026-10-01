package com.fulfillops.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The consumer half of the outbox. {@link #claim} must be called inside the same transaction as
 * the effect the event asks for, so the two commit or roll back together: either the effect
 * happened and the id is recorded, or neither did. A redelivered event loses the insert race
 * and returns false, which is how a duplicate stops being a second charge or a second reservation.
 *
 * <p>{@code inbox.processed} counts events this service has applied. A consumer whose number has
 * stopped moving while the others climb is the one to look at, which is why it is exported.
 */
@Component
public class Inbox {

    private final JdbcClient jdbc;
    private final Counter claimed;

    public Inbox(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.claimed = Counter.builder("inbox.processed")
                .description("Events this service applied, across all its handlers")
                .register(registry);
    }

    /** Returns true the first time this consumer sees the event id, false for every redelivery. */
    public boolean claim(String eventId, String consumer) {
        if (jdbc.sql("insert into inbox (event_id, consumer) values (cast(:id as uuid), :consumer) on conflict do nothing")
                .param("id", eventId)
                .param("consumer", consumer)
                .update() != 1) {
            return false;
        }
        claimed.increment();
        return true;
    }

    long claimed() {
        return (long) claimed.count();
    }
}
