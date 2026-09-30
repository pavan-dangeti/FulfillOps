package com.fulfillops.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Delivers broker messages to the {@link EventDispatcher}. The subscription, the listener
 * container and the relay only exist when messaging is enabled, so a test can turn the broker
 * off and drive the dispatcher directly against a real Postgres.
 */
@Component
@ConditionalOnProperty(name = "fulfillops.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventListener {

    private final EventDispatcher dispatcher;

    public KafkaEventListener(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = "${fulfillops.messaging.topic:fulfillops}")
    public void onMessage(String json) {
        dispatcher.dispatch(Event.fromJson(json));
    }
}
