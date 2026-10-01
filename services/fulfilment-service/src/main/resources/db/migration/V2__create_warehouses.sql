-- Warehouse capacity is operational configuration, not demo fixture data: without it the saga
-- cannot get past allocation, so a deployment started without the "demo" profile could accept
-- orders, take payment for them, and then have to refund every one. It used to be seeded by the
-- demo profile alone, which the ordering harness caught by running against a clean stack.
--
-- Capacity is fixed here rather than read from configuration, which is a simplification: see
-- TRADEOFFS.md. `on conflict` keeps this safe alongside the demo profile's own seed.
insert into warehouses (code, capacity) values
    ('WH-EAST', 500),
    ('WH-WEST', 500)
on conflict (code) do nothing;
