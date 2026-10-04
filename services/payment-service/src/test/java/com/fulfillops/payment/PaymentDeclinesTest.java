package com.fulfillops.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fulfillops.common.Event;
import com.fulfillops.common.EventDispatcher;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Every charge declines here, so each test is about what a decline does, not whether one happens. */
@SpringBootTest(properties = {"fulfillops.security.signer=true", "fulfillops.payment.decline-percent=100"})
class PaymentDeclinesTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired EventDispatcher dispatcher;
    @Autowired Payments payments;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from inbox").update();
        jdbc.sql("delete from outbox").update();
        jdbc.sql("delete from payments").update();
    }

    private static Event reserved(String order) {
        return Event.of(Event.INVENTORY_RESERVED, order,
                Event.newBody().put("sku", "SKU-1001").put("quantity", 2).put("amount", "40.00"));
    }

    private List<String> announced(String order) {
        return jdbc.sql("select type from outbox where order_number = :o order by id")
                .param("o", order).query(String.class).list();
    }

    @Test
    void aDeclineIsRecordedAndAnnouncedNotCharged() {
        dispatcher.dispatch(reserved("ORD-D1"));

        assertThat(payments.get("ORD-D1").status()).isEqualTo("DECLINED");
        assertThat(announced("ORD-D1")).containsExactly(Event.PAYMENT_REJECTED);
    }

    /**
     * Reconciliation re-announces a reservation as a new event, which the inbox does not dedupe.
     * The second request must get the stored answer, or a retry could charge a declined card.
     */
    @Test
    void aReplayedRequestDeclinesAgainAndNeverCharges() {
        dispatcher.dispatch(reserved("ORD-D2"));
        dispatcher.dispatch(reserved("ORD-D2"));

        assertThat(jdbc.sql("select count(*) from payments where order_number = 'ORD-D2'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(payments.get("ORD-D2").status()).isEqualTo("DECLINED");
        assertThat(announced("ORD-D2")).containsExactly(Event.PAYMENT_REJECTED, Event.PAYMENT_REJECTED);
    }

    @Test
    void compensatingADeclinedOrderRefundsNothing() {
        dispatcher.dispatch(reserved("ORD-D3"));
        dispatcher.dispatch(Event.of(Event.COMPENSATION_REQUESTED, "ORD-D3"));

        var payment = payments.get("ORD-D3");
        assertThat(payment.status()).isEqualTo("DECLINED");
        assertThat(payment.refundedAt()).isNull();
    }

    @Test
    void theDeclineShareIsCloseToTheConfiguredPercent() {
        long declined = IntStream.range(0, 10_000)
                .filter(i -> Payments.declines("ORD-" + i, 20))
                .count();
        assertThat(declined).isBetween(1_800L, 2_200L);
        assertThat(IntStream.range(0, 1_000).anyMatch(i -> Payments.declines("ORD-" + i, 0))).isFalse();
        assertThat(IntStream.range(0, 1_000).allMatch(i -> Payments.declines("ORD-" + i, 100))).isTrue();
    }
}
