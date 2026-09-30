package com.fulfillops.order;

import com.fulfillops.common.TokenIssuer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Order-service's read-only view of what its peers have actually done with an order.
 *
 * <p>Pricing an order needs the price, and the price belongs to the service that owns the
 * catalogue, so it is fetched rather than trusted from the request. Reconciliation needs the same
 * answers, so both use this one client rather than two mechanisms.
 *
 * <p>It signs its own short-lived {@code INTERNAL} token, because order-service is the only
 * service holding the signing key — no other service can mint one. The token is cached for a few
 * minutes because signing per call would put an RSA signature on every order creation.
 */
@Service
public class SagaPeers {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private static final String SUBJECT = "order-service";

    public record Product(String sku, String name, BigDecimal unitPrice) {
    }

    public record Reservation(String orderNumber, String status) {
    }

    public record Payment(String orderNumber, String status) {
    }

    public record Allocation(String orderNumber, String status) {
    }

    private final RestClient inventory;
    private final RestClient payments;
    private final RestClient fulfilment;
    private final TokenIssuer issuer;
    private volatile String token = "";
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SagaPeers(TokenIssuer issuer,
                     @Value("${fulfillops.inventory-url}") String inventoryUrl,
                     @Value("${fulfillops.payment-url}") String paymentUrl,
                     @Value("${fulfillops.fulfilment-url}") String fulfilmentUrl) {
        this.issuer = issuer;
        this.inventory = client(inventoryUrl);
        this.payments = client(paymentUrl);
        this.fulfilment = client(fulfilmentUrl);
    }

    private RestClient client(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl)
                .defaultRequest(spec -> spec.header("Authorization", "Bearer " + token()))
                .build();
    }

    private synchronized String token() {
        if (Instant.now().isAfter(tokenExpiresAt)) {
            token = issuer.issue(SUBJECT, List.of("INTERNAL"));
            // Re-mint well before the token actually expires, so an in-flight call cannot use one
            // that lapses mid-request.
            tokenExpiresAt = Instant.now().plus(TOKEN_LIFETIME).minus(Duration.ofMinutes(1));
        }
        return token;
    }

    public Product product(String sku) {
        return inventory.get().uri("/api/products/{sku}", sku).retrieve().body(Product.class);
    }

    /** What inventory still holds for an order: {@code null} when it holds nothing. */
    public Reservation reservation(String orderNumber) {
        return get(inventory, "/api/reservations/{order}", orderNumber, new ParameterizedTypeReference<Reservation>() {
        });
    }

    /** What payment-service recorded for an order: {@code null} when it has no record. */
    public Payment payment(String orderNumber) {
        return get(payments, "/api/payments/{order}", orderNumber, new ParameterizedTypeReference<Payment>() {
        });
    }

    /** What fulfilment-service allocated for an order: {@code null} when it has no record. */
    public Allocation allocation(String orderNumber) {
        return get(fulfilment, "/api/allocations/{order}", orderNumber, new ParameterizedTypeReference<Allocation>() {
        });
    }

    private <T> T get(RestClient client, String path, String orderNumber, ParameterizedTypeReference<T> type) {
        try {
            return client.get().uri(path, orderNumber).retrieve().body(type);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
