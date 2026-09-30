package com.fulfillops.order;

import com.fulfillops.common.Event;
import com.fulfillops.common.Outbox;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orders;
    private final SagaPeers peers;
    private final Outbox outbox;
    private final Clock clock = Clock.systemUTC();

    public OrderService(OrderRepository orders, SagaPeers peers, Outbox outbox) {
        this.orders = orders;
        this.peers = peers;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Order> search(String query) {
        if (query == null || query.isBlank()) {
            return orders.findAllByOrderByCreatedAtDesc();
        }
        String q = query.trim();
        return orders.findByOrderNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(q, q);
    }

    @Transactional(readOnly = true)
    public Order get(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderNumber));
    }

    /**
     * Creates the order and asks for its stock to be held, in one transaction.
     *
     * <p>The order is accepted even when there is not enough stock. Rejecting it here would be
     * friendlier, but it would also mean the concurrency test never exercised the part that has to
     * be right: the reserve step is the only thing standing between a burst of orders and an
     * oversell, so the order is admitted and the saga decides.
     */
    public Order create(String customerName, String email, String sku, int quantity) {
        SagaPeers.Product product = peers.product(sku);
        if (product == null) {
            throw new IllegalArgumentException("Unknown SKU: " + sku);
        }
        BigDecimal total = product.unitPrice().multiply(BigDecimal.valueOf(quantity));
        Order order = orders.saveAndFlush(new Order(nextNumber(), customerName.trim(), email.trim(),
                product.sku(), product.name(), quantity, total, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        outbox.record(Event.RESERVE_REQUESTED, order.getOrderNumber(),
                Event.newBody().put("sku", product.sku()).put("quantity", quantity));
        return order;
    }

    /** Sequence-backed so two concurrent orders cannot both be handed the same number. */
    private String nextNumber() {
        return orders.nextOrderNumber();
    }

    public Order refund(String orderNumber) {
        Order order = get(orderNumber);
        // Postgres timestamptz keeps microseconds; truncating here makes the first response match every later read.
        order.refund(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return orders.saveAndFlush(order);
    }

    public Order ship(String orderNumber) {
        Order order = get(orderNumber);
        order.ship();
        return orders.saveAndFlush(order);
    }
}
