package com.fulfillops.common;

import java.util.List;
import io.micrometer.core.instrument.Counter;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

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

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    @Bean
    Inbox inbox(JdbcClient jdbc, MeterRegistry registry) {
        return new Inbox(jdbc, registry);
    }

    @Bean
    Outbox outbox(JdbcClient jdbc,
                  KafkaTemplate<Object, Object> kafka,
                  TransactionTemplate transactions,
                  ObjectProvider<Tracer> tracer,
                  ObjectProvider<Propagator> propagator,
                  MeterRegistry registry,
                  @Value("${fulfillops.messaging.topic:fulfillops}") String topic,
                  @Value("${fulfillops.messaging.batch-size:100}") int batchSize) {
        return new Outbox(jdbc, kafka, transactions, tracer, propagator, registry, topic, batchSize);
    }

    /**
     * What happens to a message a handler cannot process. Without this the broker retries it and
     * then drops it with nothing to show. Here it is retried a few times a second apart — enough
     * for a database that is briefly away — and then published to the dead-letter topic with the
     * failure in its headers, counted and logged. An unreadable or malformed event is a permanent
     * fault, so it skips the retries. The record keeps its key, so replaying it onto the main topic
     * puts it back in its order's partition.
     */
    @Bean
    @ConditionalOnProperty(name = "fulfillops.messaging.enabled", havingValue = "true", matchIfMissing = true)
    DefaultErrorHandler deadLetters(KafkaTemplate<Object, Object> kafka, MeterRegistry registry,
                                    @Value("${fulfillops.messaging.topic:fulfillops}") String topic) {
        Counter deadLettered = Counter.builder("messaging.dead_lettered")
                .description("Events a handler could not process, published to the dead-letter topic")
                .register(registry);
        // Partition -1 lets the producer choose by key rather than reusing the source partition,
        // which the dead-letter topic need not have.
        DeadLetterPublishingRecoverer publish = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(topic + ".dlt", -1));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, failure) -> {
            publish.accept(record, failure);
            deadLettered.increment();
            log.error("dead-lettered key {} after it could not be processed: {}", record.key(), failure.getMessage());
        }, new FixedBackOff(1_000, 4));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    EventDispatcher eventDispatcher(List<EventHandler> handlers, Inbox inbox,
                                   ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new EventDispatcher(handlers, inbox, tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
