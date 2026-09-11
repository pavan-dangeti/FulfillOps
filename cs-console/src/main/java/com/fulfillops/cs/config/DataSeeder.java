package com.fulfillops.cs.config;

import com.fulfillops.cs.model.Order;
import com.fulfillops.cs.model.OrderStatus;
import com.fulfillops.cs.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedOrders(OrderRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                log.info("H2 already seeded ({} orders). Skipping.", repository.count());
                return;
            }
            List<Order> seed = List.of(
                    new Order("ORD-1050", "Ava Chen", "ava.chen@example.com", "SKU-1002",
                            "Mechanical Keyboard", 1, new BigDecimal("89.50"), OrderStatus.PROCESSING,
                            LocalDateTime.now().minusHours(2)),
                    new Order("ORD-1049", "Marcus Webb", "marcus.webb@example.com", "SKU-1001",
                            "Wireless Mouse", 3, new BigDecimal("74.97"), OrderStatus.SHIPPED,
                            LocalDateTime.now().minusHours(5)),
                    new Order("ORD-1048", "Priya Nair", "priya.nair@example.com", "SKU-1003",
                            "USB-C Hub 7-in-1", 2, new BigDecimal("90.00"), OrderStatus.PENDING,
                            LocalDateTime.now().minusHours(8)),
                    new Order("ORD-1047", "Diego Fernandez", "diego.fernandez@example.com", "SKU-1004",
                            "4K Webcam", 1, new BigDecimal("129.99"), OrderStatus.DELIVERED,
                            LocalDateTime.now().minusDays(1)),
                    new Order("ORD-1046", "Yuki Tanaka", "yuki.tanaka@example.com", "SKU-1005",
                            "Standing Desk Mat", 2, new BigDecimal("79.50"), OrderStatus.SHIPPED,
                            LocalDateTime.now().minusDays(1).minusHours(3)),
                    new Order("ORD-1045", "Hannah Schmidt", "hannah.s@example.com", "SKU-1002",
                            "Mechanical Keyboard", 1, new BigDecimal("89.50"), OrderStatus.PROCESSING,
                            LocalDateTime.now().minusDays(2)),
                    new Order("ORD-1044", "Omar Haddad", "omar.h@example.com", "SKU-1003",
                            "USB-C Hub 7-in-1", 1, new BigDecimal("45.00"), OrderStatus.PENDING,
                            LocalDateTime.now().minusDays(2).minusHours(6)),
                    new Order("ORD-1043", "Lena Kovacs", "lena.k@example.com", "SKU-1001",
                            "Wireless Mouse", 5, new BigDecimal("124.95"), OrderStatus.DELIVERED,
                            LocalDateTime.now().minusDays(3)),
                    new Order("ORD-1042", "Tomás Rivera", "tomas.r@example.com", "SKU-1004",
                            "4K Webcam", 2, new BigDecimal("259.98"), OrderStatus.SHIPPED,
                            LocalDateTime.now().minusDays(3).minusHours(2)),
                    new Order("ORD-1041", "Sofia Greco", "sofia.g@example.com", "SKU-1005",
                            "Standing Desk Mat", 1, new BigDecimal("39.75"), OrderStatus.PENDING,
                            LocalDateTime.now().minusDays(4)),
                    new Order("ORD-1040", "Wei Zhang", "wei.z@example.com", "SKU-1002",
                            "Mechanical Keyboard", 2, new BigDecimal("179.00"), OrderStatus.DELIVERED,
                            LocalDateTime.now().minusDays(5)),
                    new Order("ORD-1039", "Emily Stone", "emily.stone@example.com", "SKU-1001",
                            "Wireless Mouse", 1, new BigDecimal("24.99"), OrderStatus.REFUNDED,
                            LocalDateTime.now().minusDays(6)));
            repository.saveAll(seed);
            log.info("Seeded {} sample orders.", seed.size());
        };
    }
}