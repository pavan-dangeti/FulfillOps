package com.fulfillops.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One fact travelling between services. The id is the idempotency key: a consumer records it in
 * its inbox inside the same transaction as the effect, so a redelivery is skipped rather than
 * applied twice. {@code orderNumber} is the broker key, which is what makes a single order's events
 * arrive in the order they were written.
 */
public record Event(String id, String type, String orderNumber, JsonNode body, String traceparent) {

    public static final String RESERVE_REQUESTED = "order.reserve.requested";
    public static final String COMPENSATION_REQUESTED = "order.compensation.requested";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_REJECTED = "inventory.rejected";
    public static final String PAYMENT_CHARGED = "payment.charged";
    public static final String PAYMENT_REJECTED = "payment.rejected";
    public static final String FULFILMENT_ALLOCATED = "fulfilment.allocated";
    public static final String FULFILMENT_REJECTED = "fulfilment.rejected";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Event of(String type, String orderNumber, ObjectNode body) {
        return new Event(UUID.randomUUID().toString(), type, orderNumber, body, null);
    }

    /** With the producer's trace context attached, so a consumer's span joins the same trace. */
    public static Event of(String type, String orderNumber, ObjectNode body, String traceparent) {
        return new Event(UUID.randomUUID().toString(), type, orderNumber, body, traceparent);
    }

    public static Event of(String type, String orderNumber) {
        return of(type, orderNumber, JSON.createObjectNode(), null);
    }

    public Event traced(String parentTraceparent) {
        return new Event(id, type, orderNumber, body, parentTraceparent);
    }

    /** A fresh body for a caller to fill in before {@link #of}. */
    public static ObjectNode newBody() {
        return JSON.createObjectNode();
    }

    public static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null ? null : value.asText();
    }

    public static String required(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException("Event body is missing " + field + ": " + body);
        }
        return value;
    }

    public static int integer(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Event body is missing " + field + ": " + body);
        }
        return value.asInt();
    }

    public String toJson() {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("orderNumber", orderNumber);
        if (traceparent != null) {
            envelope.put("traceparent", traceparent);
        }
        envelope.set("body", body);
        return envelope.toString();
    }

    public static Event fromJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode parent = node.get("traceparent");
            return new Event(
                    node.get("id").asText(),
                    node.get("type").asText(),
                    node.get("orderNumber").asText(),
                    node.get("body"),
                    parent == null || parent.isNull() ? null : parent.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable event: " + json, e);
        }
    }

    public String describe() {
        return "%s(%s)".formatted(type, orderNumber);
    }

    @Override
    public String toString() {
        return describe() + " at " + Instant.now();
    }
}
