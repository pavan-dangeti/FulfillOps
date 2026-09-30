package com.fulfillops.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes events to the outbox in the caller's transaction, then drains the table to the broker.
 *
 * <p>Publishing cannot join a Postgres transaction, so a service that updated its own tables and
 * then published would have a window where the database and the broker disagree: a crash in
 * between loses a command for a change that did happen, or delivers one for a change that was
 * rolled back. Writing the event to {@code outbox} inside the same transaction closes that window
 * — the event exists if and only if the change does. The relay then moves rows to the broker,
 * which makes delivery at-least-once, and so every consumer has to be idempotent.
 */
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final JdbcClient jdbc;
    private final KafkaTemplate<Object, Object> kafka;
    private final TransactionTemplate transactions;
    private final String topic;
    private final int batchSize;

    public Outbox(JdbcClient jdbc,
                 KafkaTemplate<Object, Object> kafka,
                 TransactionTemplate transactions,
                 @Value("${fulfillops.messaging.topic:fulfillops}") String topic,
                 @Value("${fulfillops.messaging.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = transactions;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /** Records an event for publication. Must be called inside the transaction that made the change. */
    public Event record(String type, String orderNumber, ObjectNode body) {
        Event event = Event.of(type, orderNumber, body);
        jdbc.sql("insert into outbox (event_id, type, order_number, body) values (cast(:id as uuid), :type, :order, cast(:body as jsonb))")
                .param("id", event.id())
                .param("type", type)
                .param("order", orderNumber)
                .param("body", body.toString())
                .update();
        return event;
    }

    public Event record(String type, String orderNumber) {
        return record(type, orderNumber, Event.newBody());
    }

    private record Row(long id, Event event) { }

    /**
     * Publishes one batch, marking rows published in the same transaction that locked them. A
     * crash after the broker accepted a row but before the commit redelivers it, which is exactly
     * why consumers dedupe on the event id.
     */
    // Known limit: one relay thread, one blocking send per event, one batch per tick, so
    // publication rate is bounded by batch size over the tick interval. Add relay instances
    // (SKIP LOCKED keeps them from colliding) or a partitioned relay if that ceiling is reached.
    public void drain() {
        List<Row> batch = transactions.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            select id, event_id, type, order_number, body::text as body
                            from outbox where published_at is null
                            order by id
                            for update skip locked
                            limit :batch""")
                    .param("batch", batchSize)
                    .query((rs, n) -> new Row(
                            rs.getLong("id"),
                            new Event(rs.getString("event_id"), rs.getString("type"),
                                    rs.getString("order_number"), parse(rs.getString("body")))))
                    .list();
            for (Row row : rows) {
                // Blocking send, so a published marker never outruns the broker acknowledging it.
                // A failure here propagates and rolls the batch back: the rows stay unpublished
                // and go out again next tick, which redelivers rather than drops.
                send(row.event());
            }
            for (Row row : rows) {
                jdbc.sql("update outbox set published_at = now() where id = :id").param("id", row.id()).update();
            }
            return rows;
        });
        if (batch != null && !batch.isEmpty()) {
            log.debug("relayed {} event(s) to {}", batch.size(), topic);
        }
    }

    private void send(Event event) {
        try {
            kafka.send(topic, event.orderNumber(), event.toJson()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.describe(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish " + event.describe() + " to " + topic, e);
        }
    }

    public long unpublished() {        return jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox body: " + json, e);
        }
    }
}
