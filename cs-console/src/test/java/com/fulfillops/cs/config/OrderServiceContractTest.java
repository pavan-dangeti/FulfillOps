package com.fulfillops.cs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslRequestWithPath;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.fulfillops.cs.model.OrderStatus;
import com.fulfillops.cs.service.OrderService;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * Consumer side of the cs-console -> order-service contract. Each test drives
 * the console's real client code against Pact's mock server; the recorded
 * interactions are verified against the real order-service in its build.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "order-service", pactVersion = PactSpecVersion.V4)
class OrderServiceContractTest {

    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})";

    private static PactDslJsonBody order(String number, String status) {
        return new PactDslJsonBody()
                .stringValue("orderNumber", number)
                .stringType("customerName", "Priya Nair")
                .stringType("sku", "SKU-1003")
                .stringType("productName", "USB-C Hub 7-in-1")
                .integerType("quantity", 2)
                .numberType("total", 90.00)
                .stringValue("status", status)
                .stringMatcher("createdAt", ISO_INSTANT, "2026-09-28T10:15:30Z");
    }

    private static PactDslRequestWithPath authed(PactDslWithProvider builder, String state, String desc, String method, String path) {
        return builder.given(state).uponReceiving(desc).method(method).path(path)
                .matchHeader("Authorization", "Bearer .+", "Bearer token");
    }

    private OrderService client(MockServer server) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("cs", null, List.of());
        auth.setDetails(new SecurityConfig.AccessToken("token-from-login"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return new OrderService(new SecurityConfig().orderServiceClient(RestClient.builder(), server.getUrl()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // --- sign in --------------------------------------------------------------

    @Pact(consumer = "cs-console")
    V4Pact csLogin(PactDslWithProvider builder) {
        return builder.given("account cs with password cs-password and role CS")
                .uponReceiving("a CS rep signs in")
                .path("/api/auth/token").method("POST")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(new PactDslJsonBody().stringValue("username", "cs").stringValue("password", "cs-password"))
                .willRespondWith().status(200)
                .body(new PactDslJsonBody()
                        .stringType("accessToken", "eyJhbGciOiJIUzI1NiJ9.e30.sig")
                        .integerType("expiresIn", 3600)
                        .array("roles").stringValue("CS").closeArray())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "csLogin")
    void signInReturnsATokenAndRoles(MockServer server) {
        var token = SecurityConfig.login(RestClient.create(server.getUrl()), "cs", "cs-password");
        assertThat(token.accessToken()).isNotBlank();
        assertThat(token.roles()).containsExactly("CS");
    }

    @Pact(consumer = "cs-console")
    V4Pact badLogin(PactDslWithProvider builder) {
        return builder.given("account cs with password cs-password and role CS")
                .uponReceiving("a sign-in with the wrong password")
                .path("/api/auth/token").method("POST")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(new PactDslJsonBody().stringValue("username", "cs").stringValue("password", "wrong"))
                .willRespondWith().status(401)
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "badLogin")
    void wrongPasswordIsRejected(MockServer server) {
        assertThat(SecurityConfig.login(RestClient.create(server.getUrl()), "cs", "wrong")).isNull();
    }

    // --- orders ---------------------------------------------------------------

    @Pact(consumer = "cs-console")
    V4Pact listOrders(PactDslWithProvider builder) {
        return authed(builder, "demo orders exist", "a request for all orders", "GET", "/api/orders")
                .willRespondWith().status(200)
                .body(PactDslJsonArray.arrayMinLike(1)
                        .stringType("orderNumber", "ORD-1050")
                        .stringType("customerName", "Ava Chen")
                        .stringType("sku", "SKU-1002")
                        .stringType("productName", "Mechanical Keyboard")
                        .integerType("quantity", 1)
                        .numberType("total", 89.50)
                        .stringMatcher("status", "PENDING|PROCESSING|SHIPPED|DELIVERED|REFUNDED", "PROCESSING")
                        .stringMatcher("createdAt", ISO_INSTANT, "2026-09-28T10:15:30Z")
                        .closeObject())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "listOrders")
    void listsOrders(MockServer server) {
        assertThat(client(server).search(null)).isNotEmpty();
    }

    @Pact(consumer = "cs-console")
    V4Pact searchOrders(PactDslWithProvider builder) {
        return authed(builder, "demo orders exist", "a search for orders by customer name", "GET", "/api/orders").query("q=Priya")
                .willRespondWith().status(200)
                .body(PactDslJsonArray.arrayMinLike(1)
                        .stringValue("orderNumber", "ORD-1048")
                        .stringValue("customerName", "Priya Nair")
                        .integerType("quantity", 2)
                        .closeObject())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "searchOrders")
    void searchesByCustomer(MockServer server) {
        assertThat(client(server).search("Priya")).extracting(o -> o.orderNumber()).contains("ORD-1048");
    }

    @Pact(consumer = "cs-console")
    V4Pact orderDetail(PactDslWithProvider builder) {
        return authed(builder, "order ORD-1048 is PENDING", "a request for order ORD-1048 by customer service", "GET", "/api/orders/ORD-1048")
                .willRespondWith().status(200)
                .body(order("ORD-1048", "PENDING")
                        .stringType("email", "priya.nair@example.com")
                        .nullValue("refundedAt"))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "orderDetail")
    void readsOrderDetailIncludingEmail(MockServer server) {
        var order = client(server).getByOrderNumber("ORD-1048");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.email()).isNotBlank();
        assertThat(order.refundedAt()).isNull();
    }

    @Pact(consumer = "cs-console")
    V4Pact missingOrder(PactDslWithProvider builder) {
        return authed(builder, "demo orders exist", "a request for an order that does not exist", "GET", "/api/orders/ORD-0000")
                .willRespondWith().status(404)
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "missingOrder")
    void missingOrderIsNotFound(MockServer server) {
        assertThatThrownBy(() -> client(server).getByOrderNumber("ORD-0000")).isInstanceOf(NoSuchElementException.class);
    }

    @Pact(consumer = "cs-console")
    V4Pact refund(PactDslWithProvider builder) {
        return authed(builder, "order ORD-1048 is PENDING", "a refund of order ORD-1048", "POST", "/api/orders/ORD-1048/refund")
                .willRespondWith().status(200)
                .body(order("ORD-1048", "REFUNDED").stringMatcher("refundedAt", ISO_INSTANT, "2026-09-28T11:00:00Z"))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "refund")
    void refundsAnOrder(MockServer server) {
        var order = client(server).refund("ORD-1048");
        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.refundedAt()).isNotNull();
    }

    @Pact(consumer = "cs-console")
    V4Pact ship(PactDslWithProvider builder) {
        return authed(builder, "order ORD-1050 is PROCESSING", "shipping order ORD-1050", "POST", "/api/orders/ORD-1050/ship")
                .willRespondWith().status(200)
                .body(order("ORD-1050", "SHIPPED"))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "ship")
    void shipsAnOrder(MockServer server) {
        assertThat(client(server).ship("ORD-1050").status()).isEqualTo(OrderStatus.SHIPPED);
    }
}
