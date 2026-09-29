package com.fulfillops.fulfilment;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns each order one pick-and-pack slot in a warehouse. Every operation is
 * keyed by order number and idempotent, and a cancel that arrives before its
 * allocate leaves a CANCELLED tombstone, so the late allocate becomes a no-op
 * instead of consuming capacity for a dead order.
 */
@Service
public class Allocations {

    public record Allocation(String orderNumber, String sku, int quantity, String warehouse, String status,
                             Instant allocatedAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    public Allocations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Allocation allocate(String orderNumber, String sku, int quantity) {
        lock(orderNumber);
        Optional<Allocation> existing = find(orderNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        String warehouse = takeSlot()
                .orElseThrow(() -> new IllegalStateException("No fulfilment capacity for order " + orderNumber));
        jdbc.sql("insert into allocations (order_number, sku, quantity, warehouse, status) values (:order, :sku, :qty, :wh, 'ALLOCATED')")
                .param("order", orderNumber)
                .param("sku", sku)
                .param("qty", quantity)
                .param("wh", warehouse)
                .update();
        return get(orderNumber);
    }

    /** Frees the slot. Cancelling twice, or before allocating, is safe. */
    @Transactional
    public Allocation cancel(String orderNumber) {
        lock(orderNumber);
        Optional<Allocation> existing = find(orderNumber);
        if (existing.isEmpty()) {
            jdbc.sql("insert into allocations (order_number, sku, quantity, status) values (:order, 'UNKNOWN', 1, 'CANCELLED')")
                    .param("order", orderNumber)
                    .update();
        } else if (existing.get().status().equals("ALLOCATED")) {
            release(existing.get().warehouse());
            setStatus(orderNumber, "CANCELLED");
        }
        return get(orderNumber);
    }

    /** Shipping frees the slot for the next order. Only an allocated order can ship. */
    @Transactional
    public Allocation ship(String orderNumber) {
        lock(orderNumber);
        Allocation allocation = get(orderNumber);
        switch (allocation.status()) {
            case "SHIPPED" -> { }
            case "ALLOCATED" -> {
                release(allocation.warehouse());
                setStatus(orderNumber, "SHIPPED");
            }
            default -> throw new IllegalStateException("Order %s cannot ship from %s".formatted(orderNumber, allocation.status()));
        }
        return get(orderNumber);
    }

    public Allocation get(String orderNumber) {
        return find(orderNumber).orElseThrow(() -> new NoSuchElementException("No allocation for order " + orderNumber));
    }

    public Optional<Allocation> find(String orderNumber) {
        return jdbc.sql("""
                        select order_number, sku, quantity, warehouse, status, allocated_at, updated_at
                        from allocations where order_number = :order""")
                .param("order", orderNumber)
                .query(Allocation.class)
                .optional();
    }

    /**
     * Claims one slot with a conditional increment, trying warehouses in a fixed
     * order. If another transaction holds the row, the UPDATE waits and then
     * re-checks {@code allocated < capacity} against the committed value, so a
     * slot is never double-booked and a free one is never skipped.
     */
    // Known limit: one statement per warehouse; fine for a handful of warehouses, use a ranked single query if there are hundreds.
    private Optional<String> takeSlot() {
        for (String code : jdbc.sql("select code from warehouses order by code").query(String.class).list()) {
            int claimed = jdbc.sql("update warehouses set allocated = allocated + 1 where code = :code and allocated < capacity")
                    .param("code", code)
                    .update();
            if (claimed == 1) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }

    /** Serialises operations on one order (held until commit) so check-then-act on it is safe. */
    private void lock(String orderNumber) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:order))").param("order", orderNumber).query().listOfRows();
    }

    private void release(String warehouse) {
        jdbc.sql("update warehouses set allocated = allocated - 1 where code = :wh").param("wh", warehouse).update();
    }

    private void setStatus(String orderNumber, String status) {
        jdbc.sql("update allocations set status = :status, updated_at = now() where order_number = :order")
                .param("status", status)
                .param("order", orderNumber)
                .update();
    }
}
