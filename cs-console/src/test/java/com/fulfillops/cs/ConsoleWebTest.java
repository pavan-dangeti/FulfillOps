package com.fulfillops.cs;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.model.OrderStatus;
import com.fulfillops.cs.service.OrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the whole console (security, templates) with order-service mocked out. */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleWebTest {

    private static final OffsetDateTime PLACED = OffsetDateTime.of(2026, 9, 28, 10, 15, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;

    private static Order order(OrderStatus status) {
        return new Order("ORD-1048", "Priya Nair", "priya.nair@example.com", "SKU-1003", "USB-C Hub 7-in-1",
                2, new BigDecimal("90.00"), status, PLACED, null);
    }

    @Test
    void anonymousUsersAreSentToSignIn() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Sign in")));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void onlyCustomerServiceCanUseTheConsole() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CS")
    void ordersAndDetailRender() throws Exception {
        given(orders.search(null)).willReturn(List.of(order(OrderStatus.PENDING)));
        given(orders.getByOrderNumber("ORD-1048")).willReturn(order(OrderStatus.PENDING));

        mvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya Nair")))
                .andExpect(content().string(containsString("csrf-token")));
        mvc.perform(get("/orders/ORD-1048"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("priya.nair@example.com")))
                .andExpect(content().string(containsString("2026-09-28 10:15")));
    }

    @Test
    @WithMockUser(roles = "CS")
    void refundRequiresTheCsrfToken() throws Exception {
        given(orders.refund("ORD-1048")).willReturn(order(OrderStatus.REFUNDED));

        mvc.perform(post("/orders/ORD-1048/refund")).andExpect(status().isForbidden());
        mvc.perform(post("/orders/ORD-1048/refund").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REFUNDED")));
    }
}
