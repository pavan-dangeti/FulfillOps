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

    public enum Status { PENDING, PROCESSING, SHIPPED, DELIVERED, REFUNDED }

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

    @Column(nullable = false)
    private Instant createdAt;

    private Instant refundedAt;

    @Version
    private long version;

    protected Order() {
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
