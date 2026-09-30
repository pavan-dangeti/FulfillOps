package com.fulfillops.order;

import com.fulfillops.common.Event;
import com.fulfillops.common.EventHandler;
import com.fulfillops.common.Outbox;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The saga's own half: it records how far each order got and, when a step is refused, kills the
 * order and asks everyone to undo what they did.
 *
 * <p>It does not forward step messages — inventory listens to the reserve request, payment to the
 * reservation, fulfilment to the charge, so each service reacts to its predecessor directly and
 * this only keeps the order's own record honest.
 *
 * <p>Every transition reports whether it moved the order. A confirmation that arrives out of order
 * therefore changes nothing instead of throwing the transaction away, and a refusal that lands
 * after the order is already confirmed cannot undo a good order.
 */
@Component
public class OrderEvents implements EventHandler {

    private final OrderRepository orders;
    private final Outbox outbox;

    public OrderEvents(OrderRepository orders, Outbox outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of(Event.INVENTORY_RESERVED, Event.INVENTORY_REJECTED, Event.PAYMENT_CHARGED,
                Event.PAYMENT_REJECTED, Event.FULFILMENT_ALLOCATED, Event.FULFILMENT_REJECTED);
    }

    @Override
    public void handle(Event event) {
        Order order = orders.findByOrderNumber(event.orderNumber()).orElse(null);
        if (order == null) {
            // An event for an order this service has never heard of. Nothing to advance.
            return;
        }
        switch (event.type()) {
            case Event.INVENTORY_RESERVED -> order.reserved();
            case Event.PAYMENT_CHARGED -> order.paid();
            // Allocation is the last step, so a slot in hand confirms the order.
            case Event.FULFILMENT_ALLOCATED -> {
                if (order.allocated()) {
                    order.confirm();
                }
            }
            case Event.INVENTORY_REJECTED -> abandon(order, "not enough stock");
            case Event.PAYMENT_REJECTED -> abandon(order, "payment declined");
            case Event.FULFILMENT_REJECTED -> abandon(order, "no fulfilment capacity");
            default -> throw new IllegalArgumentException("Not mine: " + event.describe());
        }
    }

    /**
     * Marks the order dead and asks for compensation. The ask is one broadcast rather than a
     * command per service: each service that did work undoes its own effect, and the ones that did
     * nothing do nothing.
     */
    private void abandon(Order order, String reason) {
        if (order.fail(reason)) {
            outbox.record(Event.COMPENSATION_REQUESTED, order.getOrderNumber(),
                    Event.newBody().put("reason", reason));
        }
    }
}
