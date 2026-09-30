-- How far the order saga got. This is deliberately separate from `status`: the customer-visible
-- lifecycle (SHIPPED, REFUNDED) can change after the saga is long finished, whereas saga_step
-- only ever moves forward, so it is what says whether an order still holds stock and money.
alter table orders add column saga_step varchar(20) not null default 'STARTED';
alter table orders add column failure_reason varchar(200);
alter table orders add constraint orders_saga_step_check
    check (saga_step in ('STARTED', 'RESERVED', 'PAID', 'ALLOCATED', 'CONFIRMED', 'FAILED'));

-- FAILED is where an abandoned order ends up, so a customer can see that it will never ship.
alter table orders drop constraint orders_status_check;
alter table orders add constraint orders_status_check
    check (status in ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'REFUNDED', 'FAILED'));

create index orders_open_saga_idx on orders (created_at) where saga_step not in ('CONFIRMED', 'FAILED');

-- Order numbers come from a sequence rather than "max + 1", which two concurrent orders would
-- both compute as the same number.
create sequence order_numbers;
