package com.fulfillops.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByOrderNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(
            String orderNumber, String customerName);

    /** Sequence-backed, so two concurrent orders cannot both be handed the same number. */
    @Query(value = "select 'ORD-' || lpad(nextval('order_numbers')::text, 6, '0')", nativeQuery = true)
    String nextOrderNumber();

    /**
     * Every order the saga has not successfully completed, oldest first — including failed ones,
     * because a failed order whose compensation was never delivered still holds stock and money
     * somewhere. Only a confirmed order is genuinely settled and cannot have drifted.
     */
    @Query("""
            select o from Order o
            where o.sagaStep <> :confirmed and o.createdAt < :cutoff
            order by o.createdAt""")
    List<Order> findUnsettledBefore(@Param("cutoff") Instant cutoff,
                                    @Param("confirmed") Order.SagaStep confirmed,
                                    Pageable page);
}
