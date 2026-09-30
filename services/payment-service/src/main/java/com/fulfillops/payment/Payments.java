package com.fulfillops.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulated card payments. The order number is the idempotency key: the
 * unique constraint on {@code payments.order_number} means an order can be
 * charged at most once, however many times the charge is retried or replayed.
 */
@Service
public class Payments {

    public record Payment(String orderNumber, BigDecimal amount, String status, Instant chargedAt, Instant refundedAt) {
    }

    private final JdbcClient jdbc;

    public Payments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Payment charge(String orderNumber, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Charge amount must be positive");
        }
        jdbc.sql("""
                        insert into payments (order_number, amount, status) values (:order, :amount, 'CHARGED')
                        on conflict (order_number) do nothing""")
                .param("order", orderNumber)
                .param("amount", amount)
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
