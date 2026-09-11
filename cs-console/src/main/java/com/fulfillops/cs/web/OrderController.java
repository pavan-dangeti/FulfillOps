package com.fulfillops.cs.web;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.model.OrderStatus;
import com.fulfillops.cs.service.OrderService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/orders";
    }

    @GetMapping("/orders")
    public String orders(@RequestParam(value = "q", required = false) String q, Model model) {
        model.addAttribute("orders", service.search(q));
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("pendingCount",
                service.search(null).stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count());
        return "orders";
    }

    @GetMapping("/orders/{orderNumber}")
    public String detail(@PathVariable String orderNumber, Model model) {
        model.addAttribute("order", service.getByOrderNumber(orderNumber));
        return "order-detail";
    }

    @PostMapping("/orders/{orderNumber}/refund")
    public ModelAndView refund(@PathVariable String orderNumber) {
        Order order = service.refund(orderNumber);
        return new ModelAndView("fragments/status-badge :: badge", "order", order);
    }

    @PostMapping("/orders/{orderNumber}/ship")
    public ModelAndView ship(@PathVariable String orderNumber) {
        Order order = service.ship(orderNumber);
        return new ModelAndView("fragments/status-badge :: badge", "order", order);
    }
}