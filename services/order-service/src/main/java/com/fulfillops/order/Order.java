package com.fulfillops.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PENDING, PROCESSING, SHIPPED, DELIVERED, REFUNDED, FAILED }

    /**
     * How far the order saga got. It only moves forward, which is what makes compensation
     * computable: the order's customer-visible status cannot say whether stock is still held for
     * it, but this can.
     */
    public enum SagaStep { STARTED, RESERVED, PAID, ALLOCATED, CONFIRMED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String productName;

    private int quantity;

    @Column(nullable = false)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStep sagaStep;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant refundedAt;

    @Version
    private long version;

    protected Order() {
    }

    Order(String orderNumber, String customerName, String email, String sku, String productName,
          int quantity, BigDecimal total, Instant createdAt) {
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.email = email;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.total = total;
        this.createdAt = createdAt;
        this.status = Status.PENDING;
        this.sagaStep = SagaStep.STARTED;
    }

    /**
     * Each of these returns whether the order actually moved, and stays quiet otherwise. A
     * confirmation that arrives twice, or out of order, must not throw the transaction away and
     * must not apply a step twice — so an unexpected step is reported as "no change" and the
     * caller leaves the order alone.
     */
    public boolean reserved() {
        return advance(SagaStep.STARTED, SagaStep.RESERVED);
    }

    public boolean paid() {
        return advance(SagaStep.RESERVED, SagaStep.PAID);
    }

    public boolean allocated() {
        return advance(SagaStep.PAID, SagaStep.ALLOCATED);
    }

    /** The last step: stock is held, the money is taken and a slot is claimed. */
    public boolean confirm() {
        if (sagaStep != SagaStep.ALLOCATED) {
            return false;
        }
        sagaStep = SagaStep.CONFIRMED;
        status = Status.PROCESSING;
        return true;
    }

    /**
     * Kills the order and asks for compensation. Returns false for a rejection that arrives after
     * the order was confirmed or already failed, so a late message cannot undo a good order.
     */
    public boolean fail(String reason) {
        if (sagaStep == SagaStep.CONFIRMED || sagaStep == SagaStep.FAILED) {
            return false;
        }
        sagaStep = SagaStep.FAILED;
        status = Status.FAILED;
        failureReason = reason;
        return true;
    }

    /** Compensation is only ever requested for a dead order. */
    public boolean needsCompensation() {
        return sagaStep == SagaStep.FAILED;
    }

    private boolean advance(SagaStep expected, SagaStep next) {
        if (sagaStep != expected) {
            return false;
        }
        sagaStep = next;
        return true;
    }

    /** Refunding twice is a no-op, so a retried refund request cannot double-refund. */
    public void refund(Instant at) {
        if (status == Status.REFUNDED) {
            return;
        }
        status = Status.REFUNDED;
        refundedAt = at;
    }

    public void ship() {
        if (status == Status.SHIPPED) {
            return;
        }
        if (status != Status.PENDING && status != Status.PROCESSING) {
            throw new IllegalStateException("Order %s cannot ship from %s".formatted(orderNumber, status));
        }
        status = Status.SHIPPED;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getEmail() {
        return email;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Status getStatus() {
        return status;
    }

    public SagaStep getSagaStep() {
        return sagaStep;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
