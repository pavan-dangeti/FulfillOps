create table payments (
    id            bigint generated always as identity primary key,
    -- One payment per order: this unique key is what makes a retried charge harmless.
    order_number  varchar(32)    not null unique,
    amount        numeric(12, 2) not null check (amount > 0),
    status        varchar(20)    not null check (status in ('CHARGED', 'REFUNDED')),
    charged_at    timestamptz    not null default now(),
    refunded_at   timestamptz
);
