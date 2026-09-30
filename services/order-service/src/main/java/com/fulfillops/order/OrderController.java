package com.fulfillops.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER', 'CS', 'OPS')")
    public List<OrderView> search(@RequestParam(required = false) String q, Authentication auth) {
        boolean cs = isCs(auth);
        return service.search(q).stream().map(o -> OrderView.from(o, cs)).toList();
    }

    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasAnyRole('SELLER', 'CS', 'OPS')")
    public OrderView get(@PathVariable String orderNumber, Authentication auth) {
        return OrderView.from(service.get(orderNumber), isCs(auth));
    }

    @PostMapping("/{orderNumber}/refund")
    @PreAuthorize("hasRole('CS')")
    public OrderView refund(@PathVariable String orderNumber) {
        return OrderView.from(service.refund(orderNumber), true);
    }

    @PostMapping("/{orderNumber}/ship")
    @PreAuthorize("hasRole('CS')")
    public OrderView ship(@PathVariable String orderNumber) {
        return OrderView.from(service.ship(orderNumber), true);
    }

    private static boolean isCs(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_CS".equals(a.getAuthority()));
    }

    public record OrderView(
            String orderNumber,
            String customerName,
            // Customer email is shown to customer service only; sellers never need it (data minimisation).
            @JsonInclude(JsonInclude.Include.NON_NULL) String email,
            String sku,
            String productName,
            int quantity,
            BigDecimal total,
            String status,
            Instant createdAt,
            Instant refundedAt) {

        static OrderView from(Order o, boolean includeEmail) {
            return new OrderView(o.getOrderNumber(), o.getCustomerName(), includeEmail ? o.getEmail() : null,
                    o.getSku(), o.getProductName(), o.getQuantity(), o.getTotal(), o.getStatus().name(),
                    o.getCreatedAt(), o.getRefundedAt());
        }
    }
}
