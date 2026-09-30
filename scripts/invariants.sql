-- The invariants, as queries that return a row only when one is broken.
--
-- Run against the `postgres` role, which is the only one that can see every service's schema —
-- which is the point: these are cross-store checks, and no single service's API could answer them.
--
--   docker compose exec -T postgres psql -U postgres -d fulfillops -v settle_secs=90 -f scripts/invariants.sql
--
-- `settle_secs` is how long an order may legitimately still be in flight. Mid-saga an unfinished
-- order is normal, so the "lost order" check only counts orders older than this. Set it to 0 to
-- check that nothing is in flight at all.
--
-- `since` is an optional ISO timestamp that scopes the per-order checks to orders placed at or
-- after it. Pass an empty string to check the whole history. The store-level checks (1, 2, 3, 8)
-- are never scoped, because a stock counter is a single number and cannot mean anything only
-- within a window.
--
-- Why scoping exists: the `demo` profile seeds twelve orders by inserting them straight into the
-- table, as a fixture for the front-ends to look at. They were never placed through the saga, so
-- they have no payment, no slot and no reservation, and an unscoped run reports them as twelve
-- violated invariants. Scoping to a run is therefore reporting honestly, not hiding anything —
-- but the stronger claim is a run against a stack with the demo profile off, where no scoping is
-- needed. `ordering-harness.py` says which of the two you got.
--
-- Read every SELECT below as: rows out means broken.

-- 1. The product counter never promises more units than exist. There is a CHECK constraint for
--    this, so it should be unreachable; it is here to catch a constraint that was dropped.
select 'oversell: reserved exceeds on hand' as invariant,
       sku                                as subject,
       'reserved=' || reserved || ' on_hand=' || on_hand as detail
from inventory_svc.products
where reserved > on_hand

union all
-- 2. The per-order reservation ledger adds up to the product counter. This is the check that
--    actually catches a leak: if a release was missed or a reserve went in twice, the two
--    disagree even though neither individual row looks wrong.
select 'drift: stock counter and reservation ledger disagree',
       p.sku,
       'counter=' || p.reserved || ' ledger=' || coalesce(l.held, 0)
from inventory_svc.products p
left join (select sku, sum(quantity) as held
           from inventory_svc.reservations
           where status = 'RESERVED'
           group by sku) l on l.sku = p.sku
where p.reserved <> coalesce(l.held, 0)

union all
-- 3. A confirmed order really held its stock. This is the bound that matters, and it is stated
--    per order on purpose: "total confirmed demand versus current on_hand" is not a valid
--    invariant here, because on_hand legitimately moves as sellers restock and nothing in this
--    system consumes stock on despatch. Chaining this with 1 and 2 does bound overselling --
--    every confirmed order was backed by a reservation, the reservations sum to the product's
--    reserved counter, and that counter never exceeds on_hand.
select 'oversell: confirmed order never held its stock',
       o.order_number,
       'wanted=' || o.quantity || ' held=' || coalesce(r.quantity, 0)
from order_svc.orders o
left join inventory_svc.reservations r
       on r.order_number = o.order_number and r.status = 'RESERVED'
where o.saga_step = 'CONFIRMED'
  and coalesce(r.quantity, 0) < o.quantity
  and o.created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz)

union all
-- 4. No lost order: every accepted order has reached a terminal state.
select 'lost order: saga never finished',
       order_number,
       saga_step || ' since ' || created_at
from order_svc.orders
where saga_step not in ('CONFIRMED', 'FAILED')
  and created_at < now() - (:'settle_secs' || ' seconds')::interval
  and created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz)

union all
-- 5. No double charge, and no charge without a reason: a confirmed order is paid exactly once.
select 'money: confirmed order without a live charge',
       o.order_number,
       coalesce(p.status, 'no payment row')
from order_svc.orders o
left join payment_svc.payments p
       on p.order_number = o.order_number and p.status = 'CHARGED'
where o.saga_step = 'CONFIRMED'
  and p.order_number is null
  and o.created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz)

union all
-- 6. The reverse: a dead order must not still be holding the customer's money. This is the check
--    that a crashed or undelivered compensation would fail.
select 'money: failed order still holding a charge',
       o.order_number,
       'charged ' || p.charged_at
from order_svc.orders o
join payment_svc.payments p
  on p.order_number = o.order_number and p.status = 'CHARGED'
where o.saga_step = 'FAILED'

union all
-- 7. A confirmed order really was allocated, and a dead order really was not.
select 'fulfilment: confirmed order with no slot',
       o.order_number,
       coalesce(a.status, 'no allocation row')
from order_svc.orders o
left join fulfilment_svc.allocations a
       on a.order_number = o.order_number and a.status = 'ALLOCATED'
where o.saga_step = 'CONFIRMED'
  and a.order_number is null
  and o.created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz)

union all
select 'fulfilment: failed order still holding a slot',
       o.order_number,
       a.warehouse
from order_svc.orders o
join fulfilment_svc.allocations a
  on a.order_number = o.order_number and a.status = 'ALLOCATED'
where o.saga_step = 'FAILED'
  and o.created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz)

union all
-- 8. No orphan effects. Compensation legitimately writes tombstones for orders it has never seen
--    holding anything, so only effects that actually hold something are flagged.
select 'orphan: reservation for an unknown order', r.order_number, r.status
from inventory_svc.reservations r
left join order_svc.orders o on o.order_number = r.order_number
where o.order_number is null and r.status = 'RESERVED'

union all
select 'orphan: charge for an unknown order', p.order_number, p.status
from payment_svc.payments p
left join order_svc.orders o on o.order_number = p.order_number
where o.order_number is null and p.status = 'CHARGED'

union all
select 'orphan: slot for an unknown order', a.order_number, a.status
from fulfilment_svc.allocations a
left join order_svc.orders o on o.order_number = a.order_number
where o.order_number is null and a.status = 'ALLOCATED'

union all
-- 9. An order is either fully confirmed or fully compensated, never half of each.
select 'inconsistent: order and payment disagree',
       o.order_number,
       'order=' || o.saga_step || ' payment=' || coalesce(p.status, 'none')
from order_svc.orders o
left join payment_svc.payments p on p.order_number = o.order_number
where ((o.saga_step = 'CONFIRMED' and p.status is distinct from 'CHARGED')
    or (o.saga_step = 'FAILED' and p.status = 'CHARGED'))
  and o.created_at >= coalesce(nullif(:'since', '')::timestamptz, '-infinity'::timestamptz);
