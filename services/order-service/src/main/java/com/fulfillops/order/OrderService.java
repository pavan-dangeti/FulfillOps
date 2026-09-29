package com.fulfillops.order;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orders;
    private final Clock clock = Clock.systemUTC();

    public OrderService(OrderRepository orders) {
        this.orders = orders;
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

    public Order refund(String orderNumber) {
        Order order = get(orderNumber);
        order.refund(clock.instant());
        return orders.saveAndFlush(order);
    }

    public Order ship(String orderNumber) {
        Order order = get(orderNumber);
        order.ship();
        return orders.saveAndFlush(order);
    }
}
