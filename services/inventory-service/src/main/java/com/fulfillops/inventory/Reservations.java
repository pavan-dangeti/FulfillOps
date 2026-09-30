package com.fulfillops.inventory;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds stock for an order and gives it back, both exactly once per order.
 *
 * <p>Two things here are load-bearing. The reservation ledger keys on the order number, so a
 * repeated release releases nothing and a repeated reserve reserves once. And a release that
 * arrives before its reserve writes a RELEASED tombstone, so a reserve that turns up afterwards
 * — which a reordered message will do — is refused instead of holding stock for a dead order.
 */
@Service
public class Reservations {

    public record Reservation(String orderNumber, String sku, Integer quantity, String status) {
    }

    private final JdbcClient jdbc;

    public Reservations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the unit price the stock was reserved at, or empty when the order does not hold it.
     * The price comes back from the row this statement updated, so the amount the saga charges is
     * the catalogue's at the moment of the reservation and cannot be set by the caller.
     */
    @Transactional
    public Optional<BigDecimal> reserve(String orderNumber, String sku, int quantity) {
        lock(orderNumber);
        String held = statusOf(orderNumber);
        if ("RELEASED".equals(held)) {
            return Optional.empty();
        }
        if ("RESERVED".equals(held)) {
            return unitPriceOf(sku);
        }
        // One statement decides the outcome: the row lock makes a concurrent reserve wait, and on
        // waking it re-checks the condition against the committed value, so units are never
        // promised twice. The CHECK constraint on products is the backstop if this is ever wrong.
        Optional<BigDecimal> price = jdbc.sql("""
                        update products set reserved = reserved + :qty
                        where sku = :sku and reserved + :qty <= on_hand
                        returning unit_price""")
                .param("qty", quantity)
                .param("sku", sku)
                .query(BigDecimal.class)
                .optional();
        if (price.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("insert into reservations (order_number, sku, quantity, status) values (:order, :sku, :qty, 'RESERVED')")
                .param("order", orderNumber)
                .param("sku", sku)
                .param("qty", quantity)
                .update();
        return price;
    }

    private Optional<BigDecimal> unitPriceOf(String sku) {
        return jdbc.sql("select unit_price from products where sku = :sku")
                .param("sku", sku)
                .query(BigDecimal.class)
                .optional();
    }

    /** Returns the stock. Releasing twice, or before reserving, is safe. */
    @Transactional
    public void release(String orderNumber) {
        lock(orderNumber);
        Optional<Reservation> existing = find(orderNumber);
        if (existing.isEmpty()) {
            jdbc.sql("insert into reservations (order_number, quantity, status) values (:order, 1, 'RELEASED')")
                    .param("order", orderNumber)
                    .update();
            return;
        }
        Reservation held = existing.get();
        if ("RELEASED".equals(held.status())) {
            return;
        }
        jdbc.sql("update products set reserved = reserved - :qty where sku = :sku")
                .param("qty", held.quantity())
                .param("sku", held.sku())
                .update();
        jdbc.sql("update reservations set status = 'RELEASED', released_at = now() where order_number = :order")
                .param("order", orderNumber)
                .update();
    }

    public Optional<Reservation> find(String orderNumber) {
        return jdbc.sql("select order_number, sku, quantity, status from reservations where order_number = :order")
                .param("order", orderNumber)
                .query(Reservation.class)
                .optional();
    }

    /** True when this order still holds stock, which is what reconciliation compares against. */
    public boolean holding(String orderNumber) {
        return find(orderNumber).map(r -> "RESERVED".equals(r.status())).orElse(false);
    }

    private String statusOf(String orderNumber) {
        return find(orderNumber).map(Reservation::status).orElse(null);
    }

    /** Serialises reserve and release for one order (held until commit) so check-then-act is safe. */
    private void lock(String orderNumber) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:order))").param("order", orderNumber).query().listOfRows();
    }
}
