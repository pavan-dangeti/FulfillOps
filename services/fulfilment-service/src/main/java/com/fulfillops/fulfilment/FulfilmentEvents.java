package com.fulfillops.fulfilment;

import com.fulfillops.common.Event;
import com.fulfillops.common.EventHandler;
import com.fulfillops.common.Outbox;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Fulfilment's side of the order saga. A slot is claimed once the money has moved, and a
 * compensation releases it — {@link Allocations#cancel} already writes a tombstone when the
 * cancel beats the allocate, which is what makes a reordered pair of messages safe.
 */
@Component
public class FulfilmentEvents implements EventHandler {

    private final Allocations allocations;
    private final Outbox outbox;

    public FulfilmentEvents(Allocations allocations, Outbox outbox) {
        this.allocations = allocations;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of(Event.PAYMENT_CHARGED, Event.COMPENSATION_REQUESTED);
    }

    @Override
    public void handle(Event event) {
        switch (event.type()) {
            case Event.PAYMENT_CHARGED -> allocate(event);
            case Event.COMPENSATION_REQUESTED -> allocations.cancel(event.orderNumber());
            default -> throw new IllegalArgumentException("Not mine: " + event.describe());
        }
    }

    private void allocate(Event event) {
        String sku = Event.required(event.body(), "sku");
        int quantity = Event.integer(event.body(), "quantity");
        // An empty result means no free slot, which the saga has to hear about so it can refund
        // and release the stock.
        boolean allocated = allocations.allocate(event.orderNumber(), sku, quantity).isPresent();
        outbox.record(allocated ? Event.FULFILMENT_ALLOCATED : Event.FULFILMENT_REJECTED, event.orderNumber(),
                Event.newBody().put("sku", sku).put("quantity", quantity));
    }
}
