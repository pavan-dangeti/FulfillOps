package com.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fulfillops.common.Event;
import com.fulfillops.common.EventDispatcher;
import com.fulfillops.common.Outbox;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The order saga against a real Postgres, with the broker switched off. Events go in through the
 * same {@link EventDispatcher} production uses, so the inbox claim and the state transition are
 * exercised together rather than bypassed — that is the only way a duplicate-message test is
 * worth anything.
 */
@SpringBootTest(properties = {"fulfillops.security.signer=true", "fulfillops.messaging.enabled=false"})
class OrderSagaFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired EventDispatcher dispatcher;
    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired Outbox outbox;
    @Autowired JdbcClient jdbc;

    @MockitoBean SagaPeers peers;

    @BeforeEach
    void priceIsKnown() {
        jdbc.sql("delete from inbox").update();
        jdbc.sql("delete from outbox").update();
        jdbc.sql("delete from orders").update();
        when(peers.product("SKU-1002")).thenReturn(new SagaPeers.Product("SKU-1002", "Widget", new BigDecimal("20.00")));
    }

    private Order newOrder() {
        return service.create("Ava Chen", "ava@example.com", "SKU-1002", 2);
    }

    private List<String> pendingEvents(String orderNumber) {
        return jdbc.sql("select type from outbox where order_number = :o and published_at is null order by id")
                .param("o", orderNumber)
                .query(String.class)
                .list();
    }

    @Test
    void creatingAnOrderAsksForStockInTheSameTransaction() {
        Order order = newOrder();
        assertThat(order.getTotal()).isEqualByComparingTo("40.00");
        assertThat(order.getStatus()).isEqualTo(Order.Status.PENDING);
        assertThat(pendingEvents(order.getOrderNumber())).containsExactly(Event.RESERVE_REQUESTED);
    }

    @Test
    void theHappyPathEndsConfirmed() {
        Order order = newOrder();
        String number = order.getOrderNumber();
        dispatcher.dispatch(Event.of(Event.INVENTORY_RESERVED, number));
        dispatcher.dispatch(Event.of(Event.PAYMENT_CHARGED, number));
        dispatcher.dispatch(Event.of(Event.FULFILMENT_ALLOCATED, number));

        Order settled = orders.findByOrderNumber(number).orElseThrow();
        assertThat(settled.getSagaStep()).isEqualTo(Order.SagaStep.CONFIRMED);
        assertThat(settled.getStatus()).isEqualTo(Order.Status.PROCESSING);
    }

    @Test
    void aDuplicateMessageChangesNothing() {
        Order order = newOrder();
        String number = order.getOrderNumber();
        Event reserved = Event.of(Event.INVENTORY_RESERVED, number);
        dispatcher.dispatch(reserved);
        // The same event delivered twice: the inbox has already claimed it.
        dispatcher.dispatch(reserved);
        dispatcher.dispatch(reserved);

        assertThat(orders.findByOrderNumber(number).orElseThrow().getSagaStep())
                .isEqualTo(Order.SagaStep.RESERVED);
        assertThat(jdbc.sql("select count(*) from inbox where event_id = cast(:id as uuid)")
                .param("id", reserved.id())
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void aReorderedConfirmationIsIgnoredAndTheOrderDoesNotSkipAhead() {
        Order order = newOrder();
        String number = order.getOrderNumber();
        // payment confirms before inventory has: the broker's per-order key would normally prevent
        // this, so the state machine has to be the thing that refuses it.
        dispatcher.dispatch(Event.of(Event.PAYMENT_CHARGED, number));
        dispatcher.dispatch(Event.of(Event.FULFILMENT_ALLOCATED, number));

        assertThat(orders.findByOrderNumber(number).orElseThrow().getSagaStep())
                .isEqualTo(Order.SagaStep.STARTED);
    }

    @Test
    void aRefusalFailsTheOrderAndAsksForCompensationOnce() {
        Order order = newOrder();
        String number = order.getOrderNumber();
        dispatcher.dispatch(Event.of(Event.INVENTORY_RESERVED, number));
        dispatcher.dispatch(Event.of(Event.PAYMENT_REJECTED, number));
        dispatcher.dispatch(Event.of(Event.PAYMENT_REJECTED, number, Event.newBody()));

        Order failed = orders.findByOrderNumber(number).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Order.Status.FAILED);
        assertThat(failed.getSagaStep()).isEqualTo(Order.SagaStep.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("payment declined");
        assertThat(pendingEvents(number))
                .containsExactly(Event.RESERVE_REQUESTED, Event.COMPENSATION_REQUESTED);
    }

    @Test
    void aRefusalAfterConfirmationCannotUndoTheOrder() {
        Order order = newOrder();
        String number = order.getOrderNumber();
        dispatcher.dispatch(Event.of(Event.INVENTORY_RESERVED, number));
        dispatcher.dispatch(Event.of(Event.PAYMENT_CHARGED, number));
        dispatcher.dispatch(Event.of(Event.FULFILMENT_ALLOCATED, number));
        dispatcher.dispatch(Event.of(Event.FULFILMENT_REJECTED, number));

        Order settled = orders.findByOrderNumber(number).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Order.Status.PROCESSING);
        assertThat(pendingEvents(number)).doesNotContain(Event.COMPENSATION_REQUESTED);
    }

    @Test
    void anEventForAnUnknownOrderIsIgnored() {
        dispatcher.dispatch(Event.of(Event.INVENTORY_RESERVED, "ORD-999999"));
        assertThat(orders.count()).isZero();
    }

    @Test
    void eventsForTypesThisServiceDoesNotOwnAreSkippedWithoutFailing() {
        // inventory and fulfilment both see this type and ignore it; the dispatcher must not treat
        // an unroutable message as an error.
        dispatcher.dispatch(Event.of(Event.FULFILMENT_ALLOCATED, "ORD-999999"));
        assertThat(outbox.unpublished()).isZero();
    }
}
