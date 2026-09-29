create table orders (
    id             bigint generated always as identity primary key,
    order_number   varchar(32)    not null unique,
    customer_name  varchar(200)   not null,
    email          varchar(320)   not null,
    sku            varchar(64)    not null,
    product_name   varchar(200)   not null,
    quantity       integer        not null check (quantity > 0),
    total          numeric(12, 2) not null check (total >= 0),
    status         varchar(20)    not null
        check (status in ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'REFUNDED')),
    created_at     timestamptz    not null default now(),
    refunded_at    timestamptz,
    version        bigint         not null default 0
);

create index orders_created_at_idx on orders (created_at desc);
