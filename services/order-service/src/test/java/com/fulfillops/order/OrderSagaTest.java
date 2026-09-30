package com.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The saga's rules, with no database and no broker. Every assertion here is a case the order
 * workflow can actually hit: a confirmation that arrives twice, one that arrives early, and a
 * refusal that lands after the order is already good.
 */
class OrderSagaTest {

    private static Order order() {
        return new Order("ORD-000001", "Ava Chen", "ava@example.com", "SKU-1002", "Widget",
                2, new BigDecimal("40.00"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aNewOrderHasStartedItsSaga() {
        assertThat(order().getSagaStep()).isEqualTo(Order.SagaStep.STARTED);
        assertThat(order().getStatus()).isEqualTo(Order.Status.PENDING);
    }

    @Test
    void theHappyPathConfirmsAfterAllocation() {
        Order order = order();
        assertThat(order.reserved()).isTrue();
        assertThat(order.paid()).isTrue();
        assertThat(order.allocated()).isTrue();
        assertThat(order.confirm()).isTrue();
        assertThat(order.getSagaStep()).isEqualTo(Order.SagaStep.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.PROCESSING);
    }

    @Test
    void aRepeatedConfirmationChangesNothing() {
        Order order = order();
        order.reserved();
        // The inbox already stops a redelivery, but a second distinct event claiming the same
        // step must also be harmless rather than throwing the transaction away.
        assertThat(order.reserved()).isFalse();
        assertThat(order.getSagaStep()).isEqualTo(Order.SagaStep.RESERVED);
    }

    @Test
    void aConfirmationThatArrivesEarlyIsIgnoredRatherThanApplied() {
        Order order = order();
        // payment confirms before inventory has: the message was reordered.
        assertThat(order.paid()).isFalse();
        assertThat(order.getSagaStep()).isEqualTo(Order.SagaStep.STARTED);
    }

    @Test
    void aRefusalAfterConfirmationCannotUndoAGoodOrder() {
        Order order = order();
        order.reserved();
        order.paid();
        order.allocated();
        order.confirm();
        assertThat(order.fail("not enough stock")).isFalse();
        assertThat(order.getStatus()).isEqualTo(Order.Status.PROCESSING);
        assertThat(order.getSagaStep()).isEqualTo(Order.SagaStep.CONFIRMED);
        assertThat(order.getFailureReason()).isNull();
    }

    @Test
    void aRefusalKillsTheOrderOnlyOnce() {
        Order order = order();
        order.reserved();
        assertThat(order.fail("payment declined")).isTrue();
        assertThat(order.getStatus()).isEqualTo(Order.Status.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(Order.SagaStep.FAILED);
        assertThat(order.getFailureReason()).isEqualTo("payment declined");
        // Compensation is requested from the transition, so it must not be requested again.
        assertThat(order.fail("payment declined")).isFalse();
        assertThat(order.needsCompensation()).isTrue();
    }

    @Test
    void aConfirmedOrderIsNeverAskedToCompensate() {
        Order order = order();
        order.reserved();
        order.paid();
        order.allocated();
        order.confirm();
        assertThat(order.needsCompensation()).isFalse();
    }

    @Test
    void aFailedOrderCannotShip() {
        Order order = order();
        order.fail("not enough stock");
        org.assertj.core.api.Assertions.assertThatThrownBy(order::ship)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }
}
