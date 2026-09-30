package com.fulfillops.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /**
     * Accepts the order and returns immediately. Whether it succeeds is decided by the saga, so
     * the client polls the order rather than being told up front; 202 says "accepted", not
     * "fulfilled".
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('SELLER')")
    public OrderView create(@Valid @RequestBody NewOrder body, Authentication auth) {
        return OrderView.from(service.create(body.customerName(), body.email(), body.sku(), body.quantity()),
                isCs(auth));
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

    public record NewOrder(
            @NotBlank @Size(max = 200) String customerName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 64) String sku,
            @Min(1) int quantity) {
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
            String sagaStep,
            String failureReason,
            Instant createdAt,
            Instant refundedAt) {

        static OrderView from(Order o, boolean includeEmail) {
            return new OrderView(o.getOrderNumber(), o.getCustomerName(), includeEmail ? o.getEmail() : null,
                    o.getSku(), o.getProductName(), o.getQuantity(), o.getTotal(), o.getStatus().name(),
                    o.getSagaStep().name(), o.getFailureReason(), o.getCreatedAt(), o.getRefundedAt());
        }
    }
}
