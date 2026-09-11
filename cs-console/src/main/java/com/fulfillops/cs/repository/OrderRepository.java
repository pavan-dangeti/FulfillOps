package com.fulfillops.cs.repository;

import com.fulfillops.cs.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByOrderNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCase(
            String orderNumber, String customerName);
}