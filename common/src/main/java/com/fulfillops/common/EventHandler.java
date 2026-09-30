package com.fulfillops.common;

import java.util.Set;

/**
 * A service's reaction to the event types it owns. Handlers run inside the listener's
 * transaction, so anything a handler writes — an effect, an inbox claim, an outbox event —
 * commits or rolls back as one unit.
 */
public interface EventHandler {

    /** The event types this handler consumes. */
    Set<String> types();

    void handle(Event event);
}
