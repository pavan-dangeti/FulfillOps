-- The producer's trace context, kept with the event so the relay can restore it.
--
-- The relay usually publishes in a later tick from a different request than the one that made the
-- change, so the trace cannot be picked up from whatever happens to be current when the row is
-- sent. Capturing it at write time is what lets a consumer's span be a child of the order that
-- caused it, which is the whole point of following one order end to end.
--
-- Nullable: rows written before this migration, and any write from a thread with no active trace,
-- simply have no context to restore.
alter table outbox add column traceparent varchar(80);
