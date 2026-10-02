package com.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "fulfillops.security.signer=true")
class ReservationsTest {

    private static final int ON_HAND = 100;

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired Reservations reservations;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void oneProduct() {
        jdbc.sql("delete from reservations").update();
        jdbc.sql("delete from products").update();
        jdbc.sql("insert into products (sku, name, unit_price, on_hand) values ('SKU-1001', 'Widget', 20.00, :n)")
                .param("n", ON_HAND)
                .update();
    }

    private int reservedOnProduct() {
        return jdbc.sql("select reserved from products where sku = 'SKU-1001'").query(Integer.class).single();
    }

    @Test
    void reservingTakesStockAndRecordsTheOrder() {
        assertThat(reservations.reserve("ORD-1", "SKU-1001", 3)).contains(new BigDecimal("20.00"));
        assertThat(reservedOnProduct()).isEqualTo(3);
        assertThat(reservations.holding("ORD-1")).isTrue();
    }

    @Test
    void reservingMoreThanIsHeldIsRefused() {
        assertThat(reservations.reserve("ORD-1", "SKU-1001", ON_HAND + 1)).isEmpty();
        assertThat(reservedOnProduct()).isZero();
    }

    @Test
    void anUnknownSkuIsRefusedRatherThanRaised() {
        assertThat(reservations.reserve("ORD-1", "SKU-NOPE", 1)).isEmpty();
    }

    /**
     * The invariant the whole system rests on: with 100 units on hand, however many orders race
     * for them, exactly 100 reservations succeed and the product is never over-reserved.
     */
    // 300 is the unit-level headline; 1,000 matches the end-to-end storm, so both have a baseline.
    @ParameterizedTest
    @ValueSource(ints = {300, 1000})
    void concurrentReservationsNeverOversell(int contenders) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Optional<BigDecimal>>> attempts = IntStream.range(0, contenders)
                    .<Callable<Optional<BigDecimal>>>mapToObj(i -> () -> reservations.reserve(
                            "ORD-" + i, "SKU-1001", 1))
                    .toList();
            long granted = pool.invokeAll(attempts).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(Optional::isPresent)
                    .count();
            assertThat(granted).isEqualTo(ON_HAND);
            assertThat(reservedOnProduct()).isEqualTo(ON_HAND);
            assertThat(jdbc.sql("select count(*) from reservations where status = 'RESERVED'")
                    .query(Long.class).single()).isEqualTo(ON_HAND);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reservingTwiceForOneOrderTakesTheStockOnce() {
        reservations.reserve("ORD-1", "SKU-1001", 5);
        reservations.reserve("ORD-1", "SKU-1001", 5);
        assertThat(reservedOnProduct()).isEqualTo(5);
    }

    @Test
    void releasingGivesTheStockBackOnce() {
        reservations.reserve("ORD-1", "SKU-1001", 5);
        reservations.release("ORD-1");
        reservations.release("ORD-1");
        assertThat(reservedOnProduct()).isZero();
        assertThat(reservations.holding("ORD-1")).isFalse();
    }

    /** A compensation that overtakes its reserve must leave a tombstone, or the late reserve wins. */
    @Test
    void aReserveArrivingAfterItsReleaseIsRefused() {
        reservations.release("ORD-1");
        assertThat(reservations.reserve("ORD-1", "SKU-1001", 5)).isEmpty();
        assertThat(reservedOnProduct()).isZero();
    }

    @Test
    void theDatabaseRefusesToHoldMoreThanItHas() {
        // Proves the CHECK constraint, not the application: even a direct write cannot oversell.
        assertThatThrownBy(() -> jdbc.sql("update products set reserved = :n where sku = 'SKU-1001'")
                .param("n", ON_HAND + 1)
                .update())
                .hasMessageContaining("reserved_within_on_hand");
    }

    /**
     * The baseline the safeguard is measured against: the obvious implementation.
     *
     * <p>Read the counter, decide there is room, write it back. This is what the reserve step looks
     * like before the conditional UPDATE, and it oversells — not by a little. Every thread reads the
     * same "reserved" value, decides it is fine, and writes it, so the final counter is whichever
     * write landed last rather than the number of units actually held.
     *
     * <p>Kept as a test rather than a comment so the number is measured rather than asserted from
     * memory, and so it cannot rot into a claim that no longer matches the code.
     */
    // More contenders than units, or the naive version cannot oversell and the baseline would prove
    // nothing — 60 orders for 100 units is a case where both versions behave.
    @ParameterizedTest
    @ValueSource(ints = {300, 1000})
    void theObviousImplementationOversells(int contenders) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> naive = IntStream.range(0, contenders)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        int held = jdbc.sql("select reserved from products where sku = 'SKU-1001'")
                                .query(Integer.class).single();
                        if (held >= ON_HAND) {
                            return false;
                        }
                        jdbc.sql("update products set reserved = :n where sku = 'SKU-1001'")
                                .param("n", held + 1).update();
                        return true;
                    })
                    .toList();
            long granted = pool.invokeAll(naive).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
            long ledger = jdbc.sql("select count(*) from reservations").query(Long.class).single();

            // Printed so the baseline figure is measured on every run rather than remembered.
            System.out.printf("baseline check-then-write: %d of %d contenders were told yes for %d units%n",
                    granted, contenders, ON_HAND);
            // The point of the comparison: threads agreed they had reserved units that nobody has.
            assertThat(granted)
                    .as("check-then-write must oversell, or this baseline proves nothing")
                    .isGreaterThan(ON_HAND);
            assertThat(ledger)
                    .as("no per-order ledger exists in the naive version, so the counter is the only record")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
