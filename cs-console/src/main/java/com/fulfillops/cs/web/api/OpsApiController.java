package com.fulfillops.cs.web.api;

import com.fulfillops.cs.config.SecurityConfig;
import com.fulfillops.cs.model.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only JSON feed for the ops-floor 3D visualization. It needs no user
 * session: the console signs in to order-service with its own read-only OPS
 * account, which never sees customer emails.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsApiController {

    private final RestClient orderService;
    private final String username;
    private final String password;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public OpsApiController(RestClient orderService,
                            @Value("${fulfillops.ops-feed.username:ops}") String username,
                            @Value("${fulfillops.ops-feed.password:}") String password) {
        this.orderService = orderService;
        this.username = username;
        this.password = password;
    }

    @GetMapping("/orders")
    public List<OrderView> orders() {
        List<Order> orders = orderService.get().uri("/api/orders")
                .header("Authorization", "Bearer " + opsToken())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Order>>() {
                });
        return orders.stream().map(OrderView::from).toList();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "cs-console", "orders", orders().size());
    }

    /** Re-signs in a minute before the cached token expires. */
    private synchronized String opsToken() {
        if (Instant.now().isAfter(tokenExpires.minusSeconds(60))) {
            SecurityConfig.TokenResponse response = password.isBlank() ? null
                    : SecurityConfig.login(orderService, username, password);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ops feed account is not configured");
            }
            token = response.accessToken();
            tokenExpires = Instant.now().plusSeconds(response.expiresIn());
        }
        return token;
    }

    /** Field names are the wire contract ops-floor's LiveSource parses. */
    public record OrderView(
            String orderNumber,
            String customerName,
            String email,
            String sku,
            String productName,
            int quantity,
            BigDecimal total,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime refundedAt) {

        static OrderView from(Order o) {
            return new OrderView(o.orderNumber(), o.customerName(), "", o.sku(), o.productName(), o.quantity(),
                    o.total(), o.status().name(), o.createdAt(), o.refundedAt());
        }
    }
}
