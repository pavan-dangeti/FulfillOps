# Design

How FulfillOps is put together, why it is put together that way, and what would break it.

- [Context](#context)
- [Goals](#goals) · [Non-goals](#non-goals)
- [The consistency model](#the-consistency-model) — the part that matters most
- [How an order flows](#how-an-order-flows)
- [Failure handling](#failure-handling)
- [Storage](#storage)
- [Service boundaries](#service-boundaries)
- [Messaging](#messaging)
- [Alternatives considered](#alternatives-considered)
- [Trade-offs](#trade-offs)
- [Risks](#risks)

Companion documents: [`ordering-invariants.md`](ordering-invariants.md) is the proof, with
[`performance.md`](performance.md) the measurements. [`../TRADEOFFS.md`](../TRADEOFFS.md) records the
smaller decisions and why each went the way it did.

---

## Context

FulfillOps began as a demonstration of a problem that never needed solving: a seller dashboard and a
customer-service console, reading the same order domain from two different stores, with a 3D view
that drew a line between the orders that disagreed. The disagreement was the point, and it was
manufactured rather than real.

This is the version that takes the problem seriously instead. Sellers and CS reps now share one
order store, which removes the manufactured disagreement — and with it the reason the system
existed. What is left is a harder and more ordinary question:

> An order has to hold stock, take a customer's money and claim a slot in a warehouse. Those are
> three different databases on three different services. Something has to go wrong halfway through.

The promise this project makes is narrow and testable: **stock is never oversold, no accepted order
is lost, and no customer is charged twice** — including when services crash mid-order. Everything
else in here exists to make that claim true and checkable.

The 3D ops floor survives as a live view of the real system rather than a diagram of a fake one.

---

## Goals

1. **Prove the oversell guarantee under concurrency and failure**, with scripts anyone can rerun,
   not a claim in prose.
2. **Make one order followable end to end** across every service it touches.
3. **Keep the three existing applications working** — Angular seller SPA, Thymeleaf CS console,
   Three.js ops floor.
4. **Be explainable in an interview**, including the parts that are deliberately simple.

## Non-goals

Stated so the boundaries are visible rather than implied:

- **Not production-grade scale.** One host, one Postgres, one broker node. No partitions, no
  replication, no multi-region. The load numbers are an upper bound for a single machine.
- **No real payments.** "Payment" is a row with a unique order number. Declines are simulated at
  a configured rate; there is no card network and no partial refund.
- **No multi-tenancy, no per-customer authorisation.** Roles are SELLER, CS, OPS and INTERNAL.
- **Not a general workflow engine.** The saga is four steps and is written as four steps.
- **No blue/green deploys, no schema evolution tooling** beyond numbered Flyway migrations.
- **The UI is not the product.** It exists to drive and observe the backend.

---

## The consistency model

This is the heart of the design, so it is worth being precise about what is and is not guaranteed.

**Guaranteed, always:**

- `products.reserved <= products.on_hand`, enforced by a `CHECK` constraint. No application bug can
  oversell, because the database refuses to record an over-reservation.
- The per-order reservation ledger sums exactly to `products.reserved`.
- A confirmed order held stock, and that stock was backed by a real reservation.
- Every confirmed order is paid exactly once. The unique constraint on `payments.order_number` makes
  a retried charge a no-op rather than a second debit.
- Every failed order holds no stock and no money.

**Not guaranteed:**

- That a customer sees an order the instant it is placed. The write is committed to order-service
  and read back through several hops; the CS console may be up to 2 seconds behind by design.
- Exactly-once delivery. The system provides **at-least-once delivery with effectively-once
  effects**, which is the achievable and honest form of the claim.
- That an order progresses the instant each step succeeds. Steps are relayed on a 200 ms timer, so
  an order's worst-case journey is roughly three relay ticks plus the work itself.
- Strong consistency across services. There is no distributed transaction and no two-phase commit
  anywhere in this system, deliberately.

**The model in one sentence: a single Postgres transaction per step, at-least-once messages, and
idempotent effects, which yields the appearance of exactly-once without claiming it.**

---

## How an order flows

```
seller    order-service                       inventory        payment       fulfilment
  │             │                                 │               │               │
  │─ POST ─────►│ order + reserve command         │               │               │
  │◄─ 202 ──────│ in one transaction              │               │               │
  │             │─ order.reserve.requested ──────►│ hold stock    │               │
  │             │◄─ inventory.reserved ───────────┼──────────────►│ charge        │
  │             │◄─ payment.charged ──────────────┼───────────────┼──────────────►│ claim slot
  │             │◄─ fulfilment.allocated ─────────┼───────────────┼───────────────┤
  │             │ CONFIRMED                       │               │               │
  │             │                                 │               │               │
  │             │ on any *.rejected: FAILED,      │               │               │
  │             │ then one broadcast              │               │               │
  │             │─ order.compensation.requested ─►┼──────────────►┼──────────────►│
  │             │                                 │ release       │ refund        │ cancel
```

The steps are a **choreographed saga**: no coordinator tells anyone what to do next. Each service
listens for the event that concerns it and reacts directly — inventory to the reserve request,
payment to the reservation, fulfilment to the charge. order-service keeps the order's own record
honest and publishes only two things: the reserve request that starts the saga, and the compensation
broadcast that ends a failed one.

Three properties of this shape are worth naming:

- **The amount is set by the service that owns the price.** Inventory prices the reservation from
  the catalogue row it updated and puts the amount in the confirmation. Payment charges exactly that.
  Nothing a caller or a tampered message can set decides what a customer pays.
- **`saga_step` is separate from `status`.** `status` is what a customer sees and keeps moving after
  the saga ends — SHIPPED, then REFUNDED. `saga_step` only moves forward and is what says whether
  stock and money are still involved, which is exactly what compensation needs to know.
- **An order is accepted even when stock is short.** Rejecting at creation would be friendlier and
  would also mean the concurrency test never exercised the reserve step that prevents overselling.
  The saga decides.

---

## Failure handling

The system assumes every step will fail somewhere. Four mechanisms, in order of how often they
matter.

### 1. The outbox closes the crash window

Publishing a message cannot join a Postgres transaction. A service that wrote its tables and *then*
published has a window where the database and the broker disagree: a crash in between loses a
command for work that did happen, or delivers one for work that was rolled back.

So every service writes its event to an `outbox` table **in the same transaction as the change that
caused it**. The event exists if and only if the change does. A relay moves rows to the broker on a
timer, which makes delivery at-least-once — and that obliges every consumer to be idempotent.

This is the mechanism that actually carries recovery in the failure tests. Reconciliation was
observed to do nothing in every crash scenario, because by the time it ran, redelivery had already
finished the job.

### 2. The inbox makes a redelivery harmless

Every consumer records the event id in an `inbox` table, claimed **inside the same transaction as
the effect**. Either the effect happened and the id is recorded, or neither did. A duplicate
delivery loses the insert race and does nothing.

There is no second idempotency mechanism. This one is the whole of it, and it is exercised by tests
that dispatch through the same `EventDispatcher` production uses rather than calling handlers
directly.

### 3. The state machine refuses to move twice

Every saga transition returns whether the order actually moved, and an unexpected step returns
`false` rather than throwing. So a confirmation that arrives early changes nothing, and a refusal
that lands after the order is confirmed cannot undo a good order.

### 4. Compensation, and reconciliation underneath it

When a step is refused, the order is marked `FAILED` and one `order-compensation.requested` is
broadcast. **Each service undoes its own effect** — inventory releases, payment refunds, fulfilment
cancels — because it already holds its own record and needs only the fact that the order is dead.
A per-effect command would require someone to know which effects exist.

For that to be safe, "undo" must be keyed by the order. `reservations` is a table keyed by order
number precisely so releasing is idempotent, and so a release that overtakes its reserve leaves a
tombstone that refuses the late reserve instead of holding stock for a dead order.

Reconciliation is the last line. It compares each unsettled order against what its peers actually
hold and repairs in a defined direction — the order is the *intent*, a peer's record is the *fact*:

| Disagreement | Repair |
|---|---|
| The fact is a step the order was always going to take, and the record is behind | **Advance** the order |
| The record is ahead of a step that never happened | **Record the command again** — resumes a stalled saga |
| The order is `FAILED` and a peer still holds an effect | **Ask for compensation again** |

### What happens when a consumer cannot cope

A message a handler cannot process is retried four times, a second apart — enough for a database
that is briefly away — and then **published to `fulfillops.dlt`** with the exception in its
headers, counted in `messaging_dead_lettered_total` and logged at ERROR. An unreadable message
skips the retries, because retrying cannot fix it. The partition does not stall: orders behind a
poison message carry on, which `chaos.sh poison` checks.

The dead-lettered record keeps its key, the order number. That matters for the replay: once the
cause is fixed, putting the record back on the main topic lands it in its order's partition again,
and the consumers' idempotency makes a second delivery harmless.

```bash
# what was set aside, and why: each record as JSON, with the consumer group that failed and the
# exception in its kafka_dlt-* headers
docker compose exec redpanda rpk topic consume fulfillops.dlt -o start -n 10
# after fixing the cause: replay everything onto the main topic
docker compose exec redpanda sh -c \
  "rpk topic consume fulfillops.dlt -o start -n \$N -f '%k\t%v\n' | rpk topic produce fulfillops -f '%k\t%v\n'"
```

An order whose event was dead-lettered is not lost in the meantime: it is stranded at its last
step, and reconciliation re-sends the command that moves it on.

---

## Storage

**One Postgres instance, one schema and one login role per service.** A role has no rights outside
its own schema, checked in CI by `scripts/check-schema-isolation.sh`.

This keeps the property that matters for the design — no service can read or write another's
tables, so cross-service data flows only through APIs and events — at a fraction of the cost of a
database per service. Moving a schema to its own instance is a connection-string change.

The correctness of the stock rules is delegated to the database rather than to application code:
`CHECK (reserved <= on_hand)`, `unique (order_number)` on payments, `CHECK (allocated <= capacity)`
on warehouses, `CHECK (allocated <= capacity)` again as a named constraint on the allocation's
warehouse. Each is tested by trying to violate it directly.

### Known modelling gap

**Nothing decrements `on_hand` when an order ships.** A shipped order still counts as holding its
reservation. This is why the oversell invariant is stated per order — "this confirmed order held its
stock" — rather than as a running total of confirmed demand against `on_hand`, which would be a
false statement about a system whose stock legitimately moves.

---

## Service boundaries

| Service | Owns | Listens to | Publishes |
|---|---|---|---|
| `order-service` :8081 | orders, saga state, token issuance | reserve/pay/allocate results | `order.reserve.requested`, `order.compensation.requested` |
| `inventory-service` :8082 | products, reservations | reserve requests, compensation | `inventory.reserved`, `inventory.rejected` |
| `payment-service` :8083 | payments | reservations, compensation | `payment.charged`, `payment.rejected` |
| `fulfilment-service` :8084 | warehouses, allocations | charges, compensation | `fulfilment.allocated`, `fulfilment.rejected` |

**There is deliberately no fifth "saga orchestrator" service.** The saga lives in order-service, which
already owns the order state machine and is the natural coordinator. A service whose only job is
sequencing adds a network hop and a deployment for no new invariant.

The boundaries are drawn on **which data a service is the only authority for**, not on which nouns
exist. That is why order-service reads its peers for pricing and reconciliation — those are reads of
facts it does not own, not writes.

---

## Messaging

**Redpanda** (Kafka API, single node, topic auto-creation so a fresh `docker compose up` works with no
provisioning step), with the outbox in front of it.

- **One topic, `fulfillops`, keyed by order number**, with a `type` discriminator. The key is the
  part that matters: the broker then delivers one order's events in the order they were written,
  which is what lets the saga assume its steps arrive in sequence. Eight topics would remove a small
  amount of wasted work and add a provisioning step for no correctness gain.
- **Every service's consumer group reads every message** and discards types it does not own. Known
  cost, recorded rather than hidden.
- **The trace context rides in the event envelope**, captured at write time, because the relay
  publishes on a later tick from a different request than the one that made the change.

Known limits: the relay is one thread making one blocking send per event, so publication is bounded
by batch size over the tick interval; more relay instances are safe (`FOR UPDATE SKIP LOCKED` keeps
them off each other's rows) but that is untested at scale.

---

## Alternatives considered

| Decision | Chosen | Rejected | Why |
|---|---|---|---|
| Message transport | Redpanda + Postgres outbox | Postgres queue table alone | A broker was kept for durability, consumer offsets and replay, and because "restart the broker" is a failure case worth demonstrating. The outbox is required either way. |
| | | RabbitMQ | No log to replay from; consumer offsets are a weaker fit for an order system that must be auditable after the fact. |
| Event routing | One topic, keyed by order | One topic per type | Per-order ordering is what the saga depends on. Costs every service reading every message. |
| Idempotency | Inbox table in the consumer's transaction | Redis dedupe, unique constraints on effects | Needs no new infrastructure and commits atomically with the effect. The effect-level constraints exist too, but they cannot cover every effect. |
| Saga coordination | Choreographed, no orchestrator service | Orchestration service | The existing tables make each service's own state the coordinator; a fifth service would only sequence. |
| Compensation | Each service undoes its own effect | A central compensating command per effect | Each service already holds its own record and needs only the fact the order is dead. |
| Stock correctness | Conditional `UPDATE` + `CHECK` constraint | Pessimistic row locking in application code | One statement decides the outcome; the row lock makes a concurrent reserve re-check against the committed value. The constraint is the backstop. |
| Order pricing | Fetched from inventory | Accepted from the request | The amount charged must come from the service that owns the catalogue. |
| Service-to-service auth | `INTERNAL` role, token self-signed by order-service | Shared secret, mTLS | order-service is the only holder of the signing key, so no other service can mint one. |
| Storage | One Postgres, schema per service | Database per service | Same isolation property at a fraction of the cost; migration is a connection-string change. |
| Ops Floor | Separate Vite/React app | Angular component | Three.js render loop versus zone.js batching; documented in `TRADEOFFS.md`. |

---

## Trade-offs

The full list is in [`../TRADEOFFS.md`](../TRADEOFFS.md). The ones that cost something real:

- **The relay is the throughput ceiling**, and its ceiling is a single blocking send per event on one
  thread. Fine at the measured rates; wrong shape at ten times them.
- **Reconciliation re-reads every unconfirmed order every pass**, including old failed ones, and
  re-announces a still-incomplete one each time. Safe because consumers are idempotent, unbounded in
  cost.
- **Every service reads every message.** Wasted work, kept for the ordering guarantee and because it
  makes failure injection a single `UPDATE`.
- **Warehouse capacity is a migration, not configuration.** Real deployments need it to be
  configurable; here it is a hardcoded number a test harness has to patch.
- **The signing key is generated at startup**, so order-service cannot run as several replicas and a
  restart invalidates issued tokens.

---

## Risks

Ordered by how likely they are to matter, with what to do about each.

**1. Nobody is paged.** *High likelihood, medium impact.* A dead-lettered event and an outbox that
stops draining both raise a Prometheus alert (`deploy/observability/alerts.yml`), but no
Alertmanager is configured, so the alert is visible on Prometheus's alerts page and goes nowhere
else. *Mitigation: an Alertmanager route to whatever pages the on-call.*

**2. Fulfilment capacity is a hard, silent ceiling.** *Certain to be hit.* The load test found 219 of
1,200 orders failing on slots with 500,000 units in stock. The system behaved correctly — no slot
means fail, refund, release — but a deployment that does not size capacity gets a rising failure
rate rather than a rising queue. *Mitigation: make capacity configurable and alert on the
`fulfilment.rejected` rate.*

**3. One Postgres is one failure domain.** *Low likelihood, high impact.* Every service shares a
database. It is protected by role isolation but not by redundancy; losing it loses everything.
*Mitigation: point-in-time recovery, then a real decision about splitting by service once the
traffic justifies it.*

**4. Reconciliation is unbounded.** *Medium likelihood, low impact.* It re-reads every unconfirmed
order each pass. At the measured volumes this is nothing; at ten times them it is a load problem on
the same database. *Mitigation: record when compensation was last requested and back off.*

**5. One broker node is a single point of failure.** *Low likelihood, medium impact.* Restarting
Redpanda did not lose messages in testing, and the outbox means un-published rows are safe, but
replication is not configured. *Mitigation: a second broker and replication, or accept the outage.*

**6. The correctness proof is single-machine.** *Certain to be true, medium impact.* Both the storm
and the load test ran on one host. The oversell guarantee is enforced by the database and is
hardware-independent — it reproduced on a second machine — but the throughput numbers are not.

**7. Declines are a hash, not a card network.** *Certain, low impact.* A configured share of
orders declines, decided from the order number so a retry gets the same answer. That exercises
the compensation path from payment end to end (`chaos.sh declines`), but a real processor adds
timeouts, soft declines and charges that succeed after the client gave up, none of which are
modelled.

**8. The end-to-end latency figures are client-measured.** *Certain, low impact.* They come from the
k6 client polling `GET /api/orders/{n}` every 200 ms, so each figure carries up to 200 ms of
quantisation, which flatters the p99. The bare POST latency is reported beside them for comparison, and
a server-side measurement would be the way to remove the uncertainty entirely.