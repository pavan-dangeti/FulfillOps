package com.fulfillops.cs.service;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.model.OrderStatus;
import com.fulfillops.cs.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Order> search(String query) {
        if (query == null || query.isBlank()) {
            return repository.findAll();
        }
        String q = query.trim();
        return repository.findByOrderNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCase(q, q);
    }

    @Transactional(readOnly = true)
    public Order getByOrderNumber(String orderNumber) {
        return repository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderNumber));
    }

    public Order refund(String orderNumber) {
        Order order = getByOrderNumber(orderNumber);
        if (order.getStatus() == OrderStatus.REFUNDED) {
            return order;
        }
        order.refund(LocalDateTime.now());
        return repository.save(order);
    }

    public Order ship(String orderNumber) {
        Order order = getByOrderNumber(orderNumber);
        if (order.getStatus() != OrderStatus.REFUNDED) {
            order.ship();
            return repository.save(order);
        }
        return order;
    }
}