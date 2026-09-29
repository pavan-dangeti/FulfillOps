-- A warehouse can have `capacity` orders in pick-and-pack at once.
create table warehouses (
    code       varchar(16) primary key,
    capacity   integer not null check (capacity >= 0),
    allocated  integer not null default 0 check (allocated >= 0),
    constraint allocated_within_capacity check (allocated <= capacity)
);

create table allocations (
    id            bigint generated always as identity primary key,
    order_number  varchar(32) not null unique,
    sku           varchar(64) not null,
    quantity      integer     not null check (quantity > 0),
    -- null only for a CANCELLED tombstone written by a cancel that beat its allocate
    warehouse     varchar(16) references warehouses (code),
    status        varchar(20) not null check (status in ('ALLOCATED', 'CANCELLED', 'SHIPPED')),
    allocated_at  timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint warehouse_set_unless_cancelled check (warehouse is not null or status = 'CANCELLED')
);
