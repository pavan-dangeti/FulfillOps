package com.fulfillops.cs.web.api;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.service.OrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only JSON feed for the ops-floor 3D visualization. The Thymeleaf
 * {@link com.fulfillops.cs.web.OrderController} returns fragments and stays
 * untouched; this controller adds a wire protocol next to it.
 *
 * Entities are mapped through the {@link OrderView} record — the JPA entity is
 * never serialized directly (open-in-view is false; keep it that way).
 */
@RestController
@RequestMapping("/api/ops")
public class OpsApiController {

    private final OrderService service;

    public OpsApiController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public List<OrderView> orders() {
        return service.search(null).stream()
                .map(OrderView::from)
                .toList();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long count = service.search(null).size();
        return Map.of(
                "status", "ok",
                "service", "cs-console",
                "orders", count);
    }

    /**
     * Projection of the JPA {@link Order} for JSON consumers. Field names mirror
     * the entity; dates serialize as ISO strings via Jackson.
     */
    public record OrderView(
            String orderNumber,
            String customerName,
            String email,
            String sku,
            String productName,
            int quantity,
            BigDecimal total,
            String status,
            LocalDateTime createdAt,
            LocalDateTime refundedAt) {

        static OrderView from(Order o) {
            return new OrderView(
                    o.getOrderNumber(),
                    o.getCustomerName(),
                    o.getEmail(),
                    o.getSku(),
                    o.getProductName(),
                    o.getQuantity(),
                    o.getTotal(),
                    o.getStatus().name(),
                    o.getCreatedAt(),
                    o.getRefundedAt());
        }
    }
}
