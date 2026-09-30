package com.fulfillops.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByOrderNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(
            String orderNumber, String customerName);
}
