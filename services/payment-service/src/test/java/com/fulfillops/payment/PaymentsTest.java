package com.fulfillops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.common.TokenIssuer;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "fulfillops.security.signer=true")
@AutoConfigureMockMvc
class PaymentsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired Payments payments;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;

    @Test
    void concurrentChargesForOneOrderProduceExactlyOnePayment() throws Exception {
        BigDecimal amount = new BigDecimal("89.50");
        Callable<Payments.Payment> charge = () -> payments.charge("ORD-RACE", amount);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var results = pool.invokeAll(IntStream.range(0, 16).mapToObj(i -> charge).toList());
            for (var r : results) {
                assertThat(r.get().amount()).isEqualByComparingTo(amount);
            }
        }
        long rows = jdbc.sql("select count(*) from payments where order_number = 'ORD-RACE'").query(Long.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void replayWithADifferentAmountIsRefusedNotChargedAgain() {
        payments.charge("ORD-AMT", new BigDecimal("10.00"));
        assertThatThrownBy(() -> payments.charge("ORD-AMT", new BigDecimal("12.00")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.get("ORD-AMT").amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void refundIsIdempotent() {
        payments.charge("ORD-REF", new BigDecimal("5.00"));
        var first = payments.refund("ORD-REF");
        var second = payments.refund("ORD-REF");
        assertThat(second.status()).isEqualTo("REFUNDED");
        assertThat(second.refundedAt()).isEqualTo(first.refundedAt());
    }

    @Test
    void httpReadIsCustomerServiceOnly() throws Exception {
        payments.charge("ORD-HTTP", new BigDecimal("7.25"));
        mvc.perform(get("/api/payments/ORD-HTTP")
                        .header("Authorization", "Bearer " + tokens.issue("cs", List.of("CS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHARGED"));
        mvc.perform(get("/api/payments/ORD-HTTP")
                        .header("Authorization", "Bearer " + tokens.issue("seller", List.of("SELLER"))))
                .andExpect(status().isForbidden());
    }
}
