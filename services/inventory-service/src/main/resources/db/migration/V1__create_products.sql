create table products (
    id          bigint generated always as identity primary key,
    sku         varchar(64)    not null unique,
    name        varchar(200)   not null,
    unit_price  numeric(12, 2) not null check (unit_price >= 0),
    on_hand     integer        not null check (on_hand >= 0),
    reserved    integer        not null default 0 check (reserved >= 0),
    version     bigint         not null default 0,
    -- Last line of defence against overselling: whatever the application does,
    -- the database refuses to promise more units than it holds.
    constraint reserved_within_on_hand check (reserved <= on_hand)
);
