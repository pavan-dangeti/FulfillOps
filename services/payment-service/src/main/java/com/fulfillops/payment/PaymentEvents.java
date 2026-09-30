package com.fulfillops.payment;

import com.fulfillops.common.Event;
import com.fulfillops.common.EventHandler;
import com.fulfillops.common.Outbox;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Payment's side of the order saga. Stock is already held when a charge is requested, so the
 * amount comes from the order, not from the message: a tampered or replayed event cannot change
 * what a customer is charged. A refund it never took is not an error — compensation can arrive
 * before the charge, and asking twice must stay harmless.
 */
@Component
public class PaymentEvents implements EventHandler {

    private final Payments payments;
    private final Outbox outbox;

    public PaymentEvents(Payments payments, Outbox outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    @Override
    public Set<String> types() {
        return Set.of(Event.INVENTORY_RESERVED, Event.COMPENSATION_REQUESTED);
    }

    @Override
    public void handle(Event event) {
        switch (event.type()) {
            case Event.INVENTORY_RESERVED -> charge(event);
            case Event.COMPENSATION_REQUESTED -> payments.refundIfCharged(event.orderNumber());
            default -> throw new IllegalArgumentException("Not mine: " + event.describe());
        }
    }

    private void charge(Event event) {
        // The amount is what inventory priced at reservation time, not a number the message could
        // set. sku and quantity are carried through because the next step in the saga needs them
        // and fulfilment does not know the order.
        String sku = Event.required(event.body(), "sku");
        int quantity = Event.integer(event.body(), "quantity");
        BigDecimal amount = new BigDecimal(Event.required(event.body(), "amount"));
        // Known limit: there is no simulated decline yet, so a charge either succeeds or the
        // amount disagrees with what the order was created for. That second case is a caller bug
        // and is deliberately left to propagate — catching it here would mark this transaction
        // rollback-only and report a programming error as a declined card.
        payments.charge(event.orderNumber(), amount);
        outbox.record(Event.PAYMENT_CHARGED, event.orderNumber(),
                Event.newBody()
                        .put("sku", sku)
                        .put("quantity", quantity)
                        .put("amount", amount.toPlainString()));
    }
}
