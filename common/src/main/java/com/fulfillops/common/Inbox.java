package com.fulfillops.common;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The consumer half of the outbox. {@link #claim} must be called inside the same transaction as
 * the effect the event asks for, so the two commit or roll back together: either the effect
 * happened and the id is recorded, or neither did. A redelivered event loses the insert race
 * and returns false, which is how a duplicate stops being a second charge or a second reservation.
 */
public class Inbox {

    private final JdbcClient jdbc;

    public Inbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true the first time this consumer sees the event id, false for every redelivery. */
    public boolean claim(String eventId, String consumer) {
        return jdbc.sql("insert into inbox (event_id, consumer) values (cast(:id as uuid), :consumer) on conflict do nothing")
                .param("id", eventId)
                .param("consumer", consumer)
                .update() == 1;
    }
}
