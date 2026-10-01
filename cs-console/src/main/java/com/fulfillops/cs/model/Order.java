package com.fulfillops.cs.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** An order as order-service returns it. The console holds no order state of its own. */
public record Order(
        String orderNumber,
        String customerName,
        String email,
        String sku,
        String productName,
        int quantity,
        BigDecimal total,
        OrderStatus status,
        /** How far the order saga got. Null on orders predating the saga. */
        SagaStep sagaStep,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime refundedAt) {

    /** Mirrors order-service's own enum; an unrecognised value is a contract change, not a crash. */
    public enum SagaStep {
        STARTED, RESERVED, PAID, ALLOCATED, CONFIRMED, FAILED
    }
}
