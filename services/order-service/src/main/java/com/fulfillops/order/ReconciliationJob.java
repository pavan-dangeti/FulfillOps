package com.fulfillops.order;

import com.fulfillops.common.Event;
import com.fulfillops.common.Outbox;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares what each unsettled order claims against what its peers have actually done, and puts
 * them back in agreement.
 *
 * <p>The direction of repair is the whole design. The order's saga step is the <em>intent</em> and
 * a peer's record is the <em>fact</em>, and a disagreement is resolved two ways depending on which
 * is ahead:
 *
 * <ul>
 *   <li>the fact is a step the order was always going to take — stock is held, or money is taken,
 *       for an order whose record is behind — so the order is advanced to match;
 *   <li>the order is behind on a step that never happened, so the command that produces it is
 *       recorded again, which resumes a saga that stalled on a message it never received;
 *   <li>the order is dead but a peer still holds an effect, so compensation is asked for again.
 * </ul>
 *
 * <p>Every repair is a fresh event, so it is safe to run this repeatedly: the consumers are
 * idempotent and the state machine refuses to move twice. A grace period keeps it away from
 * sagas that are merely still in flight.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final int BATCH = 200;

    private final OrderRepository orders;
    private final SagaPeers peers;
    private final Outbox outbox;
    private final Duration grace;

    public ReconciliationJob(OrderRepository orders, SagaPeers peers, Outbox outbox,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${fulfillops.reconciliation.grace:30s}") Duration grace) {
        this.orders = orders;
        this.peers = peers;
        this.outbox = outbox;
        this.grace = grace;
    }

    public record Report(int examined, int advanced, int resumed, int recompensated) {
    }

    @Scheduled(fixedDelayString = "${fulfillops.reconciliation.interval-ms:60000}")
    public void run() {
        Report report = reconcile();
        if (report.examined() > 0) {
            log.info("reconciliation examined {} unsettled order(s): advanced {}, resumed {}, recompensated {}",
                    report.examined(), report.advanced(), report.resumed(), report.recompensated());
        }
    }

    @Transactional
    public Report reconcile() {
        Instant cutoff = Instant.now().minus(grace);
        List<Order> unsettled = orders.findUnsettledBefore(cutoff,
                Order.SagaStep.CONFIRMED, PageRequest.of(0, BATCH));
        int advanced = 0;
        int resumed = 0;
        int recompensated = 0;
        for (Order order : unsettled) {
            String number = order.getOrderNumber();
            SagaPeers.Reservation reservation = peers.reservation(number);
            SagaPeers.Payment payment = peers.payment(number);
            SagaPeers.Allocation allocation = peers.allocation(number);
            boolean holdsStock = reservation != null && "RESERVED".equals(reservation.status());
            boolean charged = payment != null && "CHARGED".equals(payment.status());
            boolean allocated = allocation != null && "ALLOCATED".equals(allocation.status());

            if (order.needsCompensation()) {
                if (holdsStock || charged || allocated) {
                    log.warn("order {} failed but a peer still holds an effect; asking again to compensate",
                            number);
                    outbox.record(Event.COMPENSATION_REQUESTED, number,
                            Event.newBody().put("reason", "reconciliation"));
                    recompensated++;
                }
                continue;
            }

            if (order.getSagaStep() == Order.SagaStep.STARTED && holdsStock && order.reserved()) {
                advanced++;
            }
            if (order.getSagaStep() == Order.SagaStep.RESERVED && charged && order.paid()) {
                advanced++;
            }
            if (order.getSagaStep() == Order.SagaStep.PAID && allocated) {
                if (order.allocated() && order.confirm()) {
                    advanced++;
                }
                continue;
            }
            if (resumes(order, holdsStock, charged, allocated)) {
                resumed++;
            }
        }
        return new Report(unsettled.size(), advanced, resumed, recompensated);
    }

    /** Records the message that should have moved this order on but never arrived. */
    private boolean resumes(Order order, boolean holdsStock, boolean charged, boolean allocated) {
        String number = order.getOrderNumber();
        String sku = order.getSku();
        int quantity = order.getQuantity();
        switch (order.getSagaStep()) {
            case STARTED -> {
                if (!holdsStock) {
                    outbox.record(Event.RESERVE_REQUESTED, number,
                            Event.newBody().put("sku", sku).put("quantity", quantity));
                    return true;
                }
            }
            case RESERVED -> {
                if (!charged) {
                    // payment-service acts on the reservation, so the reservation is what is
                    // re-announced; its own dedupe and the one-charge-per-order rule make a
                    // repeated announcement harmless.
                    outbox.record(Event.INVENTORY_RESERVED, number, amountFor(order));
                    return true;
                }
            }
            case PAID -> {
                if (!allocated) {
                    outbox.record(Event.PAYMENT_CHARGED, number, amountFor(order));
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode amountFor(Order order) {
        return Event.newBody()
                .put("sku", order.getSku())
                .put("quantity", order.getQuantity())
                .put("amount", order.getTotal().toPlainString());
    }
}
