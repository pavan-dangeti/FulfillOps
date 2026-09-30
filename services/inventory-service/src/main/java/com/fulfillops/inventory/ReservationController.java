package com.fulfillops.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only over HTTP: stock is reserved by the order workflow, not by clients. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final Reservations reservations;

    public ReservationController(Reservations reservations) {
        this.reservations = reservations;
    }

    /** 404 when the order has no reservation, which is also what "holds nothing" looks like. */
    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasAnyRole('CS', 'INTERNAL')")
    public Reservations.Reservation get(@PathVariable String orderNumber) {
        return reservations.find(orderNumber)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No reservation for order " + orderNumber));
    }
}
