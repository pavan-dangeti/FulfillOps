package com.fulfillops.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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

@SpringBootTest(properties = "fulfillops.security.signer=true")
class AllocationsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired Allocations allocations;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void twoWarehouses() {
        jdbc.sql("delete from allocations").update();
        jdbc.sql("delete from warehouses").update();
        jdbc.sql("insert into warehouses (code, capacity) values ('WH-A', 2), ('WH-B', 3)").update();
    }

    private int allocatedSlots() {
        return jdbc.sql("select sum(allocated) from warehouses").query(Integer.class).single();
    }

    @Test
    void allocatingTwiceUsesOneSlot() {
        var first = allocations.allocate("ORD-1", "SKU-1", 2).orElseThrow();
        var again = allocations.allocate("ORD-1", "SKU-1", 2).orElseThrow();
        assertThat(again.warehouse()).isEqualTo(first.warehouse());
        assertThat(allocatedSlots()).isEqualTo(1);
    }

    @Test
    void concurrentAllocationsFillCapacityExactly() throws Exception {
        int attempts = 40;
        int capacity = 5;
        try (var pool = Executors.newFixedThreadPool(16)) {
            var results = pool.invokeAll(IntStream.range(0, attempts)
                    .mapToObj(i -> (Callable<Optional<Allocations.Allocation>>) () -> allocations.allocate("ORD-C" + i, "SKU-1", 1))
                    .toList());
            long ok = 0;
            for (var r : results) {
                // Running out of room is an empty result, not a failure.
                if (r.get().isPresent()) {
                    ok++;
                }
            }
            // Exactly: never over capacity, and no free slot wrongly refused.
            assertThat(ok).isEqualTo(capacity);
        }
        assertThat(allocatedSlots()).isEqualTo(capacity);
        long rows = jdbc.sql("select count(*) from allocations where status = 'ALLOCATED'").query(Long.class).single();
        assertThat(rows).isEqualTo(capacity);
    }

    @Test
    void cancelFreesTheSlotAndIsIdempotent() {
        allocations.allocate("ORD-2", "SKU-1", 1);
        allocations.cancel("ORD-2");
        allocations.cancel("ORD-2");
        assertThat(allocations.get("ORD-2").status()).isEqualTo("CANCELLED");
        assertThat(allocatedSlots()).isZero();
    }

    @Test
    void cancelBeforeAllocateWinsSoTheLateAllocateIsANoOp() {
        allocations.cancel("ORD-3");
        var late = allocations.allocate("ORD-3", "SKU-1", 1).orElseThrow();
        assertThat(late.status()).isEqualTo("CANCELLED");
        assertThat(allocatedSlots()).isZero();
    }

    @Test
    void shipFreesTheSlotButACancelledOrderCannotShip() {
        allocations.allocate("ORD-4", "SKU-1", 1);
        assertThat(allocations.ship("ORD-4").status()).isEqualTo("SHIPPED");
        assertThat(allocatedSlots()).isZero();

        allocations.cancel("ORD-5");
        assertThatThrownBy(() -> allocations.ship("ORD-5")).isInstanceOf(IllegalStateException.class);
    }
}
