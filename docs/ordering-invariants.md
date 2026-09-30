# Ordering invariants

The claim this project makes is narrow and checkable: **stock is never oversold, no accepted
order is lost, and no customer is charged twice** — including when services crash mid-order.

Everything below is produced by scripts in this repository. Each number names the command that
reproduces it.

---

## Running it

```bash
cp .env.example .env
SPRING_PROFILES_ACTIVE= docker compose up -d --build --wait   # no demo fixtures; see "Fixtures" below

./scripts/ordering-harness.py setup --units 100               # a product with exactly 100 units
./scripts/ordering-harness.py storm --orders 1000             # 1,000 orders for those 100 units
./scripts/ordering-harness.py check                           # every invariant, exit non-zero on any
```

Failure injection, which kills services and restarts the broker while orders are in flight:

```bash
./scripts/chaos.sh                  # every scenario
./scripts/chaos.sh inventory        # one scenario
ORDERS=1000 UNITS=100 ./scripts/chaos.sh
```

In CI, `invariants` runs the storm on every push and pull request. The failure-injection suite is
slower, so it runs on demand:

```bash
gh workflow run ci --ref main -f chaos=true
```

The invariant checks themselves are in [`scripts/invariants.sql`](../scripts/invariants.sql) and can
be read, or run, on their own:

```bash
docker compose exec -T postgres psql -U postgres -d fulfillops \
  -v settle_secs=90 -v since='' -f - < scripts/invariants.sql
```

Rows out means broken. Nothing out means every invariant holds.

---

## The invariants

Each is a query in `invariants.sql` that returns a row only when it is broken. They are read
across all four schemas as the one role that can see them, because none of them is a question any
single service's API could answer.

| # | Invariant | What a violation means |
|---|---|---|
| 1 | `products.reserved` never exceeds `on_hand` | A constraint was dropped or bypassed. |
| 2 | The per-order reservation ledger sums to the product's reserved counter | A release was missed or a reserve applied twice — the check that catches a slow leak, when neither row looks wrong on its own. |
| 3 | Every confirmed order really held its stock | An order was confirmed without the goods. |
| 4 | No order is stranded outside a terminal state | A lost order. Only counts orders older than `settle_secs`, because mid-saga an unfinished order is normal. |
| 5 | Every confirmed order has exactly one live charge | Confirmed but unpaid, or paid twice. |
| 6 | No failed order still holds a charge | The customer was charged for an order that was abandoned. |
| 7 | A confirmed order has a slot, and a failed order has not | Fulfilment disagrees with the order. |
| 8 | No reservation, charge or slot exists for an unknown order | An orphan effect. |
| 9 | An order is either fully confirmed or fully compensated | Half a saga. |

Checks 1, 2, 3 and 8 bound overselling: every confirmed order was backed by a reservation, the
reservations sum to the product's counter, and that counter never exceeds what is on hand.

---

## Results

Measured on a developer laptop (Apple Silicon, Docker Desktop, all containers local). The
`invariants` CI job runs the same storm on an ubuntu-latest runner; the number below is the
developer-machine run, and the command is the same.

| Result | Measured by |
|---|---|
| **1,000 orders for 100 units → exactly 100 confirmed, 900 rejected, zero oversold** | `./scripts/ordering-harness.py storm --orders 1000 --units 100 --concurrency 100` |
| All 9 invariants hold over the whole database afterwards | `./scripts/ordering-harness.py check` |
| 1,000 orders placed in ~2.2 s at 100 concurrent clients (~450 orders/s) | same storm run, reported in its output |
| 300 orders race for 100 units in-process → exactly 100 reservations | `./mvnw -pl services/inventory-service -Dtest=ReservationsTest test` |
| A duplicated message produces one effect and one inbox row | `./mvnw -pl services/order-service -Dtest=OrderSagaFlowTest test` |
| Every reconciliation repair direction (8 cases) | `./mvnw -pl services/order-service -Dtest=ReconciliationJobTest test` |

The placement rate is an observation, not a benchmark. It is measured on a laptop with the client
and all four services sharing the same machine, so it says nothing about production throughput. The
`inventory` service is the bottleneck by design: every order's reserve step contends on one product
row, which is the point being tested. A real capacity number needs `docs/design.md`'s load-test
method and stated hardware.

### Failure injection

Every scenario fires a burst, breaks something while orders are in flight, waits for the system to
converge on its own, and then runs all nine invariants. The reported "peak stranded" is the highest
number of orders caught mid-saga, sampled from the database every 300 ms — without it a scenario
could pass by the damage landing outside the saga and meaning nothing.

| Scenario | Damage | Peak stranded | Result |
|---|---|---|---|
| `inventory` | `SIGKILL` inventory-service | 300 | all invariants held |
| `payment` | `SIGKILL` payment-service | 300 | all invariants held |
| `fulfilment` | `SIGKILL` fulfilment-service | 300 | all invariants held |
| `order` | `SIGKILL` order-service | 300 | all invariants held |
| `broker` | `docker compose restart redpanda` | 300 | all invariants held |
| `duplicate` | 30 events re-published for 10 confirmed orders | — | nothing changed |
| `reorder` | each order's first event re-announced after its last | — | nothing changed |

`SIGKILL` rather than `SIGTERM`: no shutdown hook, no flush, nothing given a chance to tidy up.

The duplicate and reorder scenarios re-publish real events by clearing `published_at`, so the relay
sends them again — a genuine redelivery, not a simulated one. That it changed nothing was checked
directly, not inferred: after re-publishing, the product's `reserved` counter was still 100 and
still equalled the ledger (a re-reserve would have made it 102), and the inbox held 7,848 rows
across 7,848 distinct events, so no event was ever applied twice.

### How the system recovered, honestly

In every crash scenario, reconciliation reported **zero repairs** — it examined the stranded
orders and found nothing to do. Recovery came from the broker redelivering uncommitted messages,
with the inbox absorbing the repeats.

That is a real result and a weaker one than "the reconciliation job saved it". The outbox plus
at-least-once delivery is the mechanism doing the work here. Reconciliation is the second line for
the cases redelivery cannot cover, and this harness did not produce one. It is covered by unit
tests instead (`ReconciliationJobTest`), which drive each repair direction directly.

One gap this suite does not reach: an event a handler cannot process is retried by the broker and
then dropped. A dead-letter topic would be the proper answer, and nothing here would notice the
loss except reconciliation.

---

## Fixtures, and why the demo profile is off

The `demo` profile seeds twelve orders by inserting them straight into the table, as something for
the front-ends to look at. They never went through the saga, so they have no payment, no slot and
no reservation, and an unscoped invariant run reports them as twelve violations. The demo seed also
ships `SKU-1005` with zero stock against two confirmed orders, which is not a state the system
could have produced.

So the unqualified run starts the stack without the demo profile. For a run that does have fixtures,
`invariants.sql` takes a `since` timestamp that scopes the per-order checks to the window under
test; the store-level checks (1, 2, 3, 8) are never scoped, because a stock counter is a single
number and cannot mean anything only within a window. `ordering-harness.py` reports which of the two
you got.

---

## What this does not prove

- **Payments are never declined.** There is no simulated decline, so a charge cannot fail for
  business reasons. The payment-triggered compensation path is unexercised; only inventory and
  fulfilment refusals have been driven end to end.
- **No latency or throughput claim.** Placement rate above is an observation on a shared laptop.
- **Single region, single Postgres, one broker node.** Nothing here says anything about partitions,
  replication or failover across availability zones.
- **No stock consumption on despatch.** Nothing decrements `on_hand` when an order ships, so
  invariant 3 is stated per order rather than as a running total — see the comment in
  `invariants.sql`.
- **Long-running reconciliation cost is unmeasured.** The job re-reads every unconfirmed order on
  each pass, including old failed ones.
