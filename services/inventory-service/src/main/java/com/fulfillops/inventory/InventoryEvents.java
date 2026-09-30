package com.fulfillops.inventory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fulfillops.common.Event;
import com.fulfillops.common.EventHandler;
import com.fulfillops.common.Outbox;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Inventory's side of the order saga. It holds stock when asked and gives it back when the order
 * is abandoned, and it compensates its own effect rather than being told which effect to undo:
 * the only fact it needs is that the order is dead.
 */
@Component
public class InventoryEvents implements EventHandler {

    private final Reservations reservations;
    private final Outbox outbox;

    public InventoryEvents(Reservations reservations, Outbox outbox) {
        this.reservations = reservations;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of(Event.RESERVE_REQUESTED, Event.COMPENSATION_REQUESTED);
    }

    @Override
    public void handle(Event event) {
        switch (event.type()) {
            case Event.RESERVE_REQUESTED -> reserve(event);
            case Event.COMPENSATION_REQUESTED -> reservations.release(event.orderNumber());
            default -> throw new IllegalArgumentException("Not mine: " + event.describe());
        }
    }

    private void reserve(Event event) {
        String sku = ProductService.normalize(Event.required(event.body(), "sku"));
        int quantity = Event.integer(event.body(), "quantity");
        // An unknown SKU simply matches no product row, so it rejects like insufficient stock
        // rather than raising. Anything genuinely unexpected is left to propagate, so the
        // transaction rolls back and the message is retried instead of being reported as "no stock".
        Optional<BigDecimal> unitPrice = reservations.reserve(event.orderNumber(), sku, quantity);
        ObjectNode result = Event.newBody().put("sku", sku).put("quantity", quantity);
        if (unitPrice.isPresent()) {
            // The amount travels with the confirmation so payment charges the catalogue price
            // that was reserved, not a number from the message.
            result.put("amount", unitPrice.get().multiply(BigDecimal.valueOf(quantity)).toPlainString());
            outbox.record(Event.INVENTORY_RESERVED, event.orderNumber(), result);
        } else {
            outbox.record(Event.INVENTORY_REJECTED, event.orderNumber(), result);
        }
    }
}
