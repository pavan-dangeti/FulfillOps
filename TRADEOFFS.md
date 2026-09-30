# Tradeoffs

## Storage: one Postgres, one schema per service

The project started with each app keeping its data in memory: the CS console
used H2 with `create-drop` and reseeded on boot, and the seller dashboard held
state in a `BehaviorSubject` that reset on refresh. That made it instantly
reproducible, but the two stores diverged by design and nothing persisted.

Orders, stock, payments and fulfilment now live in Postgres, each owned by one
service. They share **one Postgres instance** but get **one schema and one login
role each**, and a role has no rights outside its own schema
(`deploy/postgres/init.sh`, checked by `scripts/check-schema-isolation.sh`).

- **Why not a database server per service?** Separate instances give stronger
  isolation (independent upgrades, failure domains, connection limits) but cost
  more memory than a small demo host has to spare. Schema-per-service keeps the
  property that matters for design (no service can read or write another's
  tables, so cross-service data flows only through APIs) at a fraction of the
  cost. Moving a schema to its own instance later is a connection-string change.
- **Why not one shared schema?** That is what makes microservices a distributed
  monolith: any service could join on another's tables and every schema change
  would need coordinating.

**The CS console holds no data at all now.** It calls order-service with the
signed-in rep's token. The jQuery-over-Thymeleaf reasoning still holds: handing
an SSR team a full framework would be the expensive migration this project
exists to *avoid*, and a few dozen lines of jQuery deliver the interactions a
CS rep needs.

## Authentication: RS256 tokens from order-service

order-service exchanges an operator's password for a one-hour JWT signed with
RS256, and publishes only the public key at `/.well-known/jwks.json`. The other
services verify tokens against that JWKS and hold no key that could sign one,
so compromising inventory, payment or fulfilment does not let an attacker mint
tokens. (An earlier version shared one HS256 secret across all services, which
gave every service the power to forge any role.) Verifiers accept RS256 only,
which blocks the "sign HS256 with the public key" confusion attack; this, a
foreign key, a wrong issuer and expiry are covered by `TokenVerificationTest`.

The cost: the signing key is generated at startup and held in memory, so a
restart of order-service invalidates issued tokens (users sign in again) and
order-service cannot run as several replicas. Loading the key from a secret
store removes both limits.

Sign-in is throttled per account: after five consecutive wrong passwords the
account is refused (429) for a minute. That caps online guessing without
letting an attacker lock an operator out for long. The count is in memory and
per instance; per-IP limits belong at the reverse proxy in front of a public
deployment.

The seller dashboard keeps its token in memory only, so a page reload signs the
seller out. The CS console keeps the token in the server-side session, never in
the browser, with a session timeout shorter than the token's lifetime.

## Payments and fulfilment use plain JDBC

order-service and inventory-service use JPA; payment-service and
fulfilment-service use `JdbcClient`. Their core operations are single
statements whose correctness comes from the database (`INSERT … ON CONFLICT DO
NOTHING` on a unique order number, a conditional `UPDATE … WHERE allocated <
capacity`). Writing those statements directly is clearer than coaxing an ORM
into them.

## Ops Floor (Three.js)

**Why a fourth stack instead of an Angular component.** The obvious place to
put a 3D view is inside `seller-dashboard/` as another routed component — one
`npm install`, one dev server, no new port to remember. It was rejected
because Angular's change detection and zone.js are built around discrete,
batched UI updates, not a render loop mutating hundreds of `InstancedMesh`
transforms 60 times a second; fighting zone.js to keep it out of the render
path would cost more than the isolation of a separate app. `ops-floor/` is a
plain Vite + React + react-three-fiber app instead, at the cost of a third
`npm install` and a third `localhost` port.

**Why instancing over discrete meshes.** Every parcel, every SKU tower fill,
every status-lane strip is one `InstancedMesh` batch with per-instance color
and custom attributes, rather than one mesh per order. At 17 seeded orders
this wouldn't matter; it's done because the seed data is meant to grow (the
scene supports up to 512 parcels without a second draw call) and because it's
the difference between a scene that stays under the ~40 draw call budget and
one that doesn't scale past a demo.

**Why polling over WebSockets.** `LiveSource` polls `GET /api/ops/orders`
every 2.5s rather than opening a WebSocket or SSE stream. Refunds and ships
aren't high-frequency events, and a 2.5s poll is invisible against the
deliberate multi-second lane-travel animations. Switching to push would mean a
stream endpoint next to `OpsApiController` and replacing `LiveSource`'s
`setInterval` with a subscription that calls the same `replaceCs` — the
`OpsSource` interface on the frontend doesn't change either way, since
`subscribe()` already models push.

**Why the seller plane has no live wire yet.** The Ops Floor was built around
two separate stores disagreeing. Now that sellers and CS read the same order
store, a live seller plane would simply mirror the CS plane, so live mode still
polls only the CS feed and the seller plane stays seed-backed. The HUD says so
explicitly. That feed is public and read-only, so it carries initials instead
of customer names and no emails, and it is cached for two seconds so anonymous
traffic cannot multiply load on order-service. It signs in to order-service
with a dedicated read-only OPS account.

## Messaging: Redpanda, and an outbox in front of it

The order flow is a saga — hold stock, take money, claim a slot, confirm — and
each step lives in a different service, so the steps have to be told to happen.
Events go through Redpanda (Kafka API, single node, in `compose.yaml`).

The outbox is the part that actually matters, and it is not a Redpanda feature:
it is a table in the same Postgres transaction as the state change that caused
the event. Publishing cannot join a database transaction, so a service that wrote
its tables and *then* published has a window where the two disagree — a crash in
between loses a command for work that did happen, or delivers one for work that
was rolled back. Writing the event in the same transaction means the event exists
if and only if the change does. `OutboxRelay` moves rows to the broker on a
timer, which makes delivery at-least-once, which is why every consumer has to be
idempotent rather than optional.

- **Why a broker and not the outbox table alone?** The outbox could carry events
  to consumers directly, with no broker at all. A broker was kept because
  durability, consumer offsets and replay survive a consumer being down, and
  because failing the *broker* is a failure case worth demonstrating.
- **Why not RabbitMQ?** No log to replay from, and consumer offsets are a weaker
  fit for an order system that has to be auditable after the fact.
- **One topic, not one per message type.** Every event goes to `fulfillops` with
  a `type` discriminator, keyed by order number. The key is what matters: the
  broker then delivers one order's events in the order they were written, which is
  what lets the saga assume its steps arrive in sequence. The cost is that each
  service's consumer group reads every message and ignores the types it does not
  own. Eight topics would remove that waste and add a provisioning step for no
  correctness gain, so it stays as one.
- **Known limit:** the relay is one thread making one blocking send per event, so
  publication is bounded by batch size over the tick interval. More relay
  instances are safe as they are — `FOR UPDATE SKIP LOCKED` keeps them off each
  other's rows — but that is untested at scale.

## Idempotency: an inbox table, claimed in the same transaction

Every service has an `inbox` table keyed by the event id. `EventDispatcher` claims
the id and runs the handler inside one transaction, so the effect and the record
that it happened commit together. A redelivery loses the insert race and does
nothing. This is the only idempotency mechanism; there is no second one.

Tests drive events through `EventDispatcher` rather than calling handlers
directly, with the broker switched off, so the claim and the state change are
always exercised together. A test that called the handler twice would prove
nothing about duplicate delivery.

## The order's saga step is separate from its status

`orders.status` is what a customer or a CS rep sees, and it keeps moving
afterwards — an order is SHIPPED, then REFUNDED. `orders.saga_step` only ever
moves forward, from `STARTED` through `RESERVED`, `PAID`, `ALLOCATED` to
`CONFIRMED` or `FAILED`. It is what says whether stock and money are still
involved, which is exactly what compensation needs to know and what `status`
cannot tell it.

Every transition returns whether the order actually moved, and a step that is not
the expected one returns `false` instead of throwing. That is the defence
against reordered messages: a confirmation that arrives early changes nothing,
and a refusal that lands after the order is already confirmed cannot undo it.

## Compensation: the service that did the work undoes it

There is no orchestrator service. order-service records how far each order got,
and when a step is refused it marks the order `FAILED` and broadcasts one
`order.compensation.requested`. Each service that did work releases its own
effect — inventory releases the reservation, payment refunds, fulfilment cancels
the slot — and the ones that did nothing do nothing. A per-effect command
(`release stock for ORD-x`) would need a service to know which effects exist;
this way each service already has that and needs only the fact that the order is
dead.

For this to be safe, "undo" has to be keyed by the order, not by a counter.
`reservations` is a table keyed by order number for that reason: releasing
twice releases once, and a release that overtakes its reserve leaves a tombstone
so the late reserve is refused instead of holding stock for a dead order.

- **A refusal is an answer, not an exception.** `Allocations.allocate` returns
  empty when there is no free slot rather than throwing. This is not a style
  preference: an exception thrown from a nested `@Transactional` call marks the
  *caller's* transaction rollback-only, and catching it there does not undo that.
  The first version of this threw, and the result was a saga step that could not
  record its own refusal — the inbox claim rolled back with it, so the message
  was retried forever and the order sat at `PAID` holding the customer's money.
  The same reasoning keeps a caller bug in `Payments.charge` (a charge for a
  different amount) propagating loudly instead of being reported as a declined
  card.

## Reconciliation: facts catch up with intent

`ReconciliationJob` compares each unsettled order against what its peers
actually hold, and the direction of repair is the whole design. The order's
`saga step` is the intent and a peer's record is the fact, so:

- the fact is a step the order was always going to take (stock is held, money is
  taken) and the order's record is behind — the order is **advanced** to match;
- the order's record is ahead of a step that never happened — the command that
  produces it is **recorded again**, which resumes a saga stalled on a message it
  never received;
- the order is `FAILED` and a peer still holds an effect — compensation is
  **asked for again**.

Failed orders are examined as well as in-flight ones. That is the whole reason
the job exists for them: an order killed mid-compensation still has stock and
money somewhere, and the first version of the query excluded exactly those.

- **Known limits:** every order that is not confirmed is re-read on each pass,
  including old failed ones, and an order that stays incomplete is re-announced
  every pass. That is safe because every consumer is idempotent, but it is
  unbounded work. A production version would record when compensation was last
  requested and back off.

## Pricing comes from the service that owns it

An order's total is fetched from inventory at creation rather than accepted from
the request, and inventory's reservation returns the unit price from the row it
updated. The amount payment charges therefore originates in the service that owns
the catalogue and cannot be set by a caller or by a tampered message. The order
is still accepted when there is not enough stock, because rejecting it there would
mean the concurrency test never exercised the reserve step that actually prevents
overselling.

## Why fulfilment capacity is a migration, not demo data

Warehouse capacity used to be seeded by the `demo` profile alone. A deployment started without that
profile could therefore accept orders, take payment for them, discover there was no slot to
allocate, and have to refund every one — a system that only works when dressed up for a demo. The
ordering harness caught it by running against a clean stack and confirming zero orders.

Capacity is operational configuration, so it now ships in a normal migration. The simplification
is that the numbers are fixed in SQL rather than read from configuration; a real deployment would
set them per warehouse.

**Related:** nothing decrements `on_hand` when an order ships. A shipped order still counts as
holding its reservation. That is why the oversell invariant in `scripts/invariants.sql` is stated
per order — "this confirmed order held its stock" — rather than as a running total of confirmed
demand against current `on_hand`, which would be a false statement about a system whose stock
legitimately moves.

## A peer being down is 503, not 500

order-service reads inventory to price an order, so inventory being down fails the request. That
was answering 500, which tells the caller *they* did something wrong and sends them looking in the
wrong place. `ApiErrors` maps `ResourceAccessException` to 503: the call was fine, the dependency is
not up. Found by the failure-injection suite, which killed inventory mid-burst.

## One topic, and every service reading all of it

Each service's consumer group subscribes to the single `fulfillops` topic and discards the types it
does not own. That is wasted work at scale and it is deliberate here: with one topic, re-publishing
an event to test a duplicate is one `update` against the outbox, and the broker's per-key ordering
is what lets the saga assume its steps arrive in sequence. Splitting into a topic per type would buy
a little efficiency and cost a provisioning step, and would make the failure tests harder to write.
The trade is recorded rather than hidden.
