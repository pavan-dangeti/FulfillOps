package com.fulfillops.fulfilment;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only over HTTP: allocation is driven by the order workflow, not by clients. */
@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

    private final Allocations allocations;

    public AllocationController(Allocations allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasRole('CS')")
    public Allocations.Allocation get(@PathVariable String orderNumber) {
        return allocations.get(orderNumber);
    }
}
