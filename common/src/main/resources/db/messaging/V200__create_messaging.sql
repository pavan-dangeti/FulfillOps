-- The transactional outbox: written in the same transaction as the state change that caused it,
-- so a crash can never leave a committed change with no message, or a message for a change
-- that was rolled back. The relay publishes rows from here and marks them published.
create table outbox (
    id            bigint generated always as identity primary key,
    -- The event id is also the idempotency key consumers use, so a redelivery is recognisable.
    event_id      uuid        not null unique,
    type          varchar(64) not null,
    -- Every event for one order carries the same key, so the broker keeps them in order.
    order_number  varchar(32) not null,
    body          jsonb       not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz
);

-- Relay reads unpublished rows in batches, claiming them with SKIP LOCKED so several
-- publishers can drain the same table without handing out the same row twice.
create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- The inbox: a consumer records an event id here in the same transaction as its business effect.
-- A duplicate delivery loses the insert race and is skipped, so an effect happens at most once.
create table inbox (
    event_id      uuid        not null primary key,
    consumer      varchar(64) not null,
    processed_at  timestamptz not null default now()
);
