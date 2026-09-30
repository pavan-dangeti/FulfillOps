-- Orders that already existed before the saga existed have no saga_step, so the column default
-- left every one of them looking like it was still running. A settled order did finish its saga;
-- only the ones still PENDING are genuinely in flight.
--
-- This has to sort after V100, because V100 is the demo seed and those rows are inserted after
-- the column was added. Editing V100 instead would break the checksum of a migration that has
-- already run.
update orders
set saga_step = 'CONFIRMED'
where saga_step = 'STARTED'
  and status in ('PROCESSING', 'SHIPPED', 'DELIVERED', 'REFUNDED');
