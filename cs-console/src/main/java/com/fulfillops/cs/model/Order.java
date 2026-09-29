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
        OffsetDateTime createdAt,
        OffsetDateTime refundedAt) {
}
