package com.fulfillops.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routes an event to the handler that owns its type, exactly once, inside one span.
 *
 * <p>The inbox claim happens here, in the same transaction as the handler's writes, so an event
 * and its effect commit together. That is the whole idempotency mechanism: a redelivery finds the
 * id already claimed and does nothing, whatever the handler would have done. Tests dispatch
 * through this same method, so they exercise the dedupe path rather than bypassing it.
 *
 * <p>The span is built here rather than left to spring-kafka's listener observation, because the
 * event's trace context is carried in the envelope where this class can see it. That keeps the
 * chain working regardless of whether the container is configured to observe records, and it puts
 * the span boundary where the transaction boundary already is.
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, EventHandler> handlers = new HashMap<>();
    private final Inbox inbox;
    private final Tracer tracer;
    private final Propagator propagator;

    public EventDispatcher(List<EventHandler> handlers, Inbox inbox,
                           Tracer tracer, Propagator propagator) {
        this.inbox = inbox;
        this.tracer = tracer;
        this.propagator = propagator;
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
        inSpan(event, () -> handler.handle(event));
    }

    /**
     * Runs the handler with a span that continues the producer's trace, so one order reads as one
     * trace across every service it touched. With no context to continue — a redelivery of an old
     * event, or a write from an untraced thread — it simply runs, and starts a trace of its own if
     * something is already tracing.
     */
    private void inSpan(Event event, Runnable action) {
        if (tracer == null || propagator == null) {
            action.run();
            return;
        }
        Span.Builder builder = event.traceparent() == null
                ? tracer.spanBuilder()
                : propagator.extract(Map.of("traceparent", event.traceparent()), Map::get);
        Span span = builder.name("saga " + event.type()).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } finally {
            // Closing the scope only restores the previous context; it does not finish the span,
            // and an unfinished span is never exported. Without this the whole chain is silent.
            span.end();
        }
    }
}