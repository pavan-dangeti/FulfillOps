package com.fulfillops.cs.service;

import com.fulfillops.cs.model.Order;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Order operations for the signed-in CS rep, delegated to order-service with
 * the rep's own access token (added by the RestClient interceptor).
 */
@Service
public class OrderService {

    private static final ParameterizedTypeReference<List<Order>> ORDER_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient orderService;

    public OrderService(RestClient orderService) {
        this.orderService = orderService;
    }

    public List<Order> search(String query) {
        return orderService.get()
                .uri(b -> b.path("/api/orders")
                        .queryParamIfPresent("q", Optional.ofNullable(query).filter(q -> !q.isBlank()))
                        .build())
                .retrieve()
                .body(ORDER_LIST);
    }

    public Order getByOrderNumber(String orderNumber) {
        try {
            return orderService.get().uri("/api/orders/{n}", orderNumber).retrieve().body(Order.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NoSuchElementException("Order not found: " + orderNumber);
        }
    }

    public Order refund(String orderNumber) {
        return orderService.post().uri("/api/orders/{n}/refund", orderNumber).retrieve().body(Order.class);
    }

    /** A refunded order cannot ship; order-service answers 409 and the badge stays as it is. */
    public Order ship(String orderNumber) {
        try {
            return orderService.post().uri("/api/orders/{n}/ship", orderNumber).retrieve().body(Order.class);
        } catch (HttpClientErrorException.Conflict e) {
            return getByOrderNumber(orderNumber);
        }
    }
}
