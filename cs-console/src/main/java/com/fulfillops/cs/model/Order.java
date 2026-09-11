package com.fulfillops.cs.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String email;

    private String sku;

    private String productName;

    private int quantity;

    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime refundedAt;

    protected Order() {
    }

    public Order(String orderNumber, String customerName, String email, String sku,
                 String productName, int quantity, BigDecimal total, OrderStatus status,
                 LocalDateTime createdAt) {
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.email = email;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.total = total;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
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

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void refund(LocalDateTime at) {
        this.status = OrderStatus.REFUNDED;
        this.refundedAt = at;
    }

    public void ship() {
        if (this.status == OrderStatus.PROCESSING || this.status == OrderStatus.PENDING) {
            this.status = OrderStatus.SHIPPED;
        }
    }
}