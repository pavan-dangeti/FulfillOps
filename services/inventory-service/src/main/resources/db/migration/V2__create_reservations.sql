-- One row per order, so releasing stock is idempotent for an order rather than a counter tweak.
-- Without this, compensation could not be made safe: "undo the reservation" needs to know what
-- was reserved and whether it is still held, and `products.reserved` alone cannot say that.
create table reservations (
    order_number  varchar(32) primary key,
    -- null only on a tombstone written by a release that arrived before its reserve
    sku           varchar(64),
    quantity      integer     not null check (quantity > 0),
    status        varchar(20) not null check (status in ('RESERVED', 'RELEASED')),
    reserved_at   timestamptz not null default now(),
    released_at   timestamptz
);
