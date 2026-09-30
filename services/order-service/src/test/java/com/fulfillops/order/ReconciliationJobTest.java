package com.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fulfillops.common.Event;
import com.fulfillops.common.Outbox;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * What reconciliation does when an order's own record and its peers' records disagree.
 *
 * <p>Each test sets up the drift by hand rather than by breaking a running service, which is the
 * point: the job has to cope with disagreement it did not cause, and the broker is off here so the
 * only thing under test is its judgement.
 */
@SpringBootTest(properties = {
        "fulfillops.security.signer=true",
        "fulfillops.messaging.enabled=false",
        "fulfillops.reconciliation.grace=1s"})
class ReconciliationJobTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired ReconciliationJob job;
    @Autowired OrderRepository orders;
    @Autowired Outbox outbox;
    @Autowired JdbcClient jdbc;

    @MockitoBean SagaPeers peers;

    /** Older than the grace period, so the job will look at it. */
    private static final Instant OLD = Instant.now().minus(Duration.ofHours(2));

    @BeforeEach
    void clean() {
        jdbc.sql("delete from outbox").update();
        jdbc.sql("delete from orders").update();
        when(peers.reservation(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(peers.payment(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(peers.allocation(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
    }

    private Order orderAt(Order.SagaStep step) {
        Order order = new Order(nextNumber(), "Ava Chen", "ava@example.com", "SKU-1001", "Widget",
                1, new BigDecimal("20.00"), OLD);
        switch (step) {
            case RESERVED -> order.reserved();
            case PAID -> {
                order.reserved();
                order.paid();
            }
            case ALLOCATED -> {
                order.reserved();
                order.paid();
                order.allocated();
            }
            case FAILED -> order.fail("not enough stock");
            default -> {
            }
        }
        return orders.saveAndFlush(order);
    }

    private int sequence = 0;

    private String nextNumber() {
        return "ORD-" + (++sequence);
    }

    private List<String> pending() {
        return jdbc.sql("select type from outbox order by id").query(String.class).list();
    }

    @Test
    void anOrderBehindItsPeersIsAdvanced() {
        Order order = orderAt(Order.SagaStep.RESERVED);
        // The charge landed but the order never heard about it.
        when(peers.payment(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Payment(order.getOrderNumber(), "CHARGED"));

        assertThat(job.reconcile().advanced()).isEqualTo(1);
        assertThat(orders.findByOrderNumber(order.getOrderNumber()).orElseThrow().getSagaStep())
                .isEqualTo(Order.SagaStep.PAID);
    }

    @Test
    void anOrderAllocatedButUnconfirmedIsConfirmed() {
        Order order = orderAt(Order.SagaStep.PAID);
        when(peers.allocation(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Allocation(order.getOrderNumber(), "ALLOCATED"));

        assertThat(job.reconcile().advanced()).isEqualTo(1);
        Order repaired = orders.findByOrderNumber(order.getOrderNumber()).orElseThrow();
        assertThat(repaired.getSagaStep()).isEqualTo(Order.SagaStep.CONFIRMED);
        assertThat(repaired.getStatus()).isEqualTo(Order.Status.PROCESSING);
    }

    @Test
    void aStalledSagaIsResumedByRecordingTheCommandItNeverGot() {
        Order order = orderAt(Order.SagaStep.STARTED);

        assertThat(job.reconcile().resumed()).isEqualTo(1);
        assertThat(pending()).containsExactly(Event.RESERVE_REQUESTED);
    }

    @Test
    void aFailedOrderThatAPeerStillHoldsIsCompensatedAgain() {
        Order order = orderAt(Order.SagaStep.FAILED);
        when(peers.reservation(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Reservation(order.getOrderNumber(), "RESERVED"));

        assertThat(job.reconcile().recompensated()).isEqualTo(1);
        assertThat(pending()).containsExactly(Event.COMPENSATION_REQUESTED);
    }

    @Test
    void aFailedOrderWithNothingLeftToUndoIsLeftAlone() {
        Order order = orderAt(Order.SagaStep.FAILED);
        assertThat(job.reconcile().recompensated()).isZero();
        assertThat(pending()).isEmpty();
    }

    /**
     * Stock held and money taken, with no slot allocated: the peers agree with the order and the
     * saga is still mid-flight, so the last step is re-announced rather than left waiting.
     */
    @Test
    void anIncompleteOrderIsResumedEvenWhenPeersAgree() {
        Order order = orderAt(Order.SagaStep.PAID);
        when(peers.reservation(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Reservation(order.getOrderNumber(), "RESERVED"));
        when(peers.payment(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Payment(order.getOrderNumber(), "CHARGED"));

        ReconciliationJob.Report report = job.reconcile();
        assertThat(report.examined()).isEqualTo(1);
        assertThat(report.resumed()).isEqualTo(1);
        assertThat(pending()).containsExactly(Event.PAYMENT_CHARGED);
    }

    @Test
    void aConfirmedOrderIsNotEvenExamined() {
        Order order = orderAt(Order.SagaStep.ALLOCATED);
        order.confirm();
        orders.saveAndFlush(order);

        assertThat(job.reconcile().examined()).isZero();
    }

    @Test
    void runningTwiceProducesNoFurtherRepairs() {
        Order order = orderAt(Order.SagaStep.PAID);
        when(peers.allocation(order.getOrderNumber()))
                .thenReturn(new SagaPeers.Allocation(order.getOrderNumber(), "ALLOCATED"));

        job.reconcile();
        jdbc.sql("delete from outbox").update();
        ReconciliationJob.Report second = job.reconcile();
        // Already confirmed, so the second pass has nothing to find.
        assertThat(second.examined()).isZero();
        assertThat(pending()).isEmpty();
        assertThat(outbox.unpublished()).isZero();
    }
}
