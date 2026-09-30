package com.fulfillops.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routes an event to the handler that owns its type, exactly once.
 *
 * <p>The inbox claim happens here, in the same transaction as the handler's writes, so an event
 * and its effect commit together. That is the whole idempotency mechanism: a redelivery finds the
 * id already claimed and does nothing, whatever the handler would have done. Tests dispatch
 * through this same method, so they exercise the dedupe path rather than bypassing it.
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, EventHandler> handlers = new HashMap<>();
    private final Inbox inbox;

    public EventDispatcher(List<EventHandler> handlers, Inbox inbox) {
        this.inbox = inbox;
        for (EventHandler handler : handlers) {
            for (String type : handler.types()) {
                EventHandler clash = this.handlers.put(type, handler);
                if (clash != null) {
                    throw new IllegalStateException("Two handlers claim " + type + ": "
                            + clash.getClass().getSimpleName() + " and " + handler.getClass().getSimpleName());
                }
            }
        }
    }

    @Transactional
    public void dispatch(Event event) {
        EventHandler handler = handlers.get(event.type());
        if (handler == null) {
            log.debug("ignoring {}: no handler in this service", event.describe());
            return;
        }
        if (!inbox.claim(event.id(), handler.getClass().getSimpleName())) {
            log.debug("ignoring {}: already processed", event.describe());
            return;
        }
        handler.handle(event);
    }
}
