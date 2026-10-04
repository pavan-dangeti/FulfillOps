package com.fulfillops.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulated card payments. The order number is the idempotency key: the
 * unique constraint on {@code payments.order_number} means an order can be
 * charged at most once, however many times the charge is retried or replayed.
 *
 * <p>A configurable share of charges is declined. The decision is a function of the order number
 * and is stored under the same key, so asking again — a redelivery, or reconciliation
 * re-announcing the reservation — always gets the first answer and never turns a decline into a
 * charge.
 */
@Service
public class Payments {

    public record Payment(String orderNumber, BigDecimal amount, String status, Instant chargedAt, Instant refundedAt) {
    }

    private final JdbcClient jdbc;
    private final int declinePercent;

    public Payments(JdbcClient jdbc, @Value("${fulfillops.payment.decline-percent:0}") int declinePercent) {
        if (declinePercent < 0 || declinePercent > 100) {
            throw new IllegalArgumentException("decline-percent must be 0..100, not " + declinePercent);
        }
        this.jdbc = jdbc;
        this.declinePercent = declinePercent;
    }

    /** Deterministic per order: String.hashCode is specified, so every run declines the same orders. */
    static boolean declines(String orderNumber, int percent) {
        return Math.floorMod(orderNumber.hashCode(), 100) < percent;
    }

    @Transactional
    public Payment charge(String orderNumber, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Charge amount must be positive");
        }
        jdbc.sql("""
                        insert into payments (order_number, amount, status) values (:order, :amount, :status)
                        on conflict (order_number) do nothing""")
                .param("order", orderNumber)
                .param("amount", amount)
                .param("status", declines(orderNumber, declinePercent) ? "DECLINED" : "CHARGED")
                .update();
        Payment payment = get(orderNumber);
        if (payment.amount().compareTo(amount) != 0) {
            // A replay with a different amount is a caller bug; never charge twice to "fix" it.
            throw new IllegalStateException("Order %s was already charged %s, not %s"
                    .formatted(orderNumber, payment.amount(), amount));
        }
        return payment;
    }

    /** Refunding a refunded payment is a no-op. */
    @Transactional
    public Payment refund(String orderNumber) {
        jdbc.sql("update payments set status = 'REFUNDED', refunded_at = now() where order_number = :order and status = 'CHARGED'")
                .param("order", orderNumber)
                .update();
        return get(orderNumber);
    }

    /**
     * Refunds only if there is a charge, and says so either way. Compensation can legitimately
     * arrive before the charge does, so "nothing to refund" is an answer, not a failure.
     */
    @Transactional
    public Optional<Payment> refundIfCharged(String orderNumber) {
        Optional<Payment> payment = find(orderNumber);
        if (payment.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(refund(orderNumber));
    }

    public Payment get(String orderNumber) {
        return find(orderNumber).orElseThrow(() -> new NoSuchElementException("No payment for order " + orderNumber));
    }

    public Optional<Payment> find(String orderNumber) {
        return jdbc.sql("select order_number, amount, status, charged_at, refunded_at from payments where order_number = :order")
                .param("order", orderNumber)
                .query(Payment.class)
                .optional();
    }
}
