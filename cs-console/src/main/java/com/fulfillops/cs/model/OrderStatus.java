package com.fulfillops.cs.model;

public enum OrderStatus {
    PENDING,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    REFUNDED,
    /** The saga could not complete and any payment or stock was released. */
    FAILED
}