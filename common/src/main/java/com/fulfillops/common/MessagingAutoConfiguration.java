package com.fulfillops.common;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Messaging shared by every service: the outbox table it writes events to, the inbox table it
 * deduplicates on, and the dispatcher that runs a handler at most once per event id.
 *
 * <p>The {@link KafkaTemplate} is Spring Boot's own, whose default serializers are already
 * String/String — events are JSON strings, so nothing here has to configure a producer.
 */
@AutoConfiguration
@EnableScheduling
@Import({KafkaEventListener.class, OutboxRelay.class})
public class MessagingAutoConfiguration {

    @Bean
    Inbox inbox(JdbcClient jdbc) {
        return new Inbox(jdbc);
    }

    @Bean
    Outbox outbox(JdbcClient jdbc,
                  KafkaTemplate<Object, Object> kafka,
                  TransactionTemplate transactions,
                  @Value("${fulfillops.messaging.topic:fulfillops}") String topic,
                  @Value("${fulfillops.messaging.batch-size:100}") int batchSize) {
        return new Outbox(jdbc, kafka, transactions, topic, batchSize);
    }

    @Bean
    EventDispatcher eventDispatcher(List<EventHandler> handlers, Inbox inbox) {
        return new EventDispatcher(handlers, inbox);
    }
}
