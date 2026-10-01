package com.fulfillops.cs.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.model.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** The ops feed is unauthenticated, so it must not carry personal data. */
class OpsFeedTest {

    @Test
    void publishesInitialsAndNoEmail() {
        var order = new Order("ORD-1048", "Priya Nair", "priya.nair@example.com", "SKU-1003", "USB-C Hub 7-in-1",
                2, new BigDecimal("90.00"), OrderStatus.PENDING, Order.SagaStep.RESERVED, null,
                OffsetDateTime.now(), null);
        var view = OpsApiController.OrderView.from(order);
        assertThat(view.customerName()).isEqualTo("P. N.");
        assertThat(view.email()).isEmpty();
    }

    @Test
    void initialsHandleSpacingAndCase() {
        assertThat(OpsApiController.OrderView.initials("  tomás   rivera ")).isEqualTo("T. R.");
        assertThat(OpsApiController.OrderView.initials("Madonna")).isEqualTo("M.");
    }
}
