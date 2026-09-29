package com.fulfillops.payment;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only over HTTP: charges and refunds are driven by the order workflow, not by clients. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final Payments payments;

    public PaymentController(Payments payments) {
        this.payments = payments;
    }

    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasRole('CS')")
    public Payments.Payment get(@PathVariable String orderNumber) {
        return payments.get(orderNumber);
    }
}
