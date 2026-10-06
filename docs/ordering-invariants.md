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
| **The same, on a different machine: 100 confirmed, 900 failed** | the `invariants` CI job (ubuntu-latest) |
| 1,000 orders placed in 2.1 s at 100 concurrent clients (~450/s) | same storm run, reported in its output |
| 1,000 orders placed in 9.0 s at 100 concurrent clients (~111/s) | same storm run on the CI runner |
| 300 and 1,000 orders race for 100 units in-process → exactly 100 reservations | `./mvnw -pl services/inventory-service -am -Dtest=ReservationsTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| A duplicated message produces one effect and one inbox row | `./mvnw -pl services/order-service -am -Dtest=OrderSagaFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| Every reconciliation repair direction, and the scheduled path that saves them (10 cases) | `./mvnw -pl services/order-service -am -Dtest=ReconciliationJobTest -Dsurefire.failIfNoSpecifiedTests=false test` |

**The correctness result is the same on both machines; the rate is not** — ~450/s on an Apple
Silicon laptop sharing the machine with all four services, ~111/s on a CI runner. That gap is the
useful part: the guarantee does not depend on the hardware, and the throughput is a property of the
machine rather than of the design. Both runs confirmed exactly 100 units.

The placement rate is an observation, not a benchmark. The `inventory` service is the bottleneck by
design: every order's reserve step contends on one product row, which is the point being tested. A
real capacity number needs a load-test method and stated hardware, which is the next phase.

### Failure injection

Every scenario fires a burst, waits until all of it has been placed, breaks something while those
orders are still moving through the saga, waits for the system to converge on its own, and then runs
all nine invariants. Every wait is for a condition — orders placed, a service healthy again, the
outbox drained — never a fixed number of seconds, so the same script holds on a 10-core laptop and a
2-core machine. The reported "peak stranded" is the highest
number of orders caught mid-saga, sampled from the database every 300 ms — without it a scenario
could pass by the damage landing outside the saga and meaning nothing.

| Scenario | Damage | Peak stranded | Result |
|---|---|---|---|
| `inventory` | `SIGKILL` inventory-service | 300 | exactly 100 confirmed, all invariants held |
| `payment` | `SIGKILL` payment-service | 300 | exactly 100 confirmed, all invariants held |
| `fulfilment` | `SIGKILL` fulfilment-service | 300 | exactly 100 confirmed, all invariants held |
| `order` | `SIGKILL` order-service | 300 | exactly 100 confirmed, all invariants held |
| `broker` | `docker compose restart redpanda` | 300 | exactly 100 confirmed, all invariants held |
| `duplicate` | 30 events re-published for 10 confirmed orders | — | nothing changed |
| `reorder` | each order's first event re-announced after its last | — | nothing changed |
| `declines` | 20% of payments declined, 100 orders for 100 units | — | 15–34 declined per run; each failed and was compensated, every other order confirmed |
| `poison` | an unreadable message published to the topic | — | dead-lettered once by each of the 4 consumers; the next burst confirmed exactly 100 |

Reproduce with `./scripts/chaos.sh`, or one scenario with `./scripts/chaos.sh declines`. The table is
from three full runs on fresh stacks (Apple M5, 10 cores, Docker with 8 GB), each after a storm and
one or three k6 runs had loaded the stack, before and after the memory tuning in
[`performance.md`](performance.md). All three passed every scenario, and so did the `invariants` CI job on an ubuntu-latest runner
(`gh workflow run ci -f chaos=true`) and a box capped at 2 CPUs and 8 GB, the size of the smallest
Codespace, where each crash caught 263–299 of the 300 orders mid-saga and the suite took 340 s. A fourth local run, made before the last harness fix below,
failed three scenarios; that failure is explained there.

`SIGKILL` rather than `SIGTERM`: no shutdown hook, no flush, nothing given a chance to tidy up.

The duplicate and reorder scenarios re-publish real events by clearing `published_at`, so the relay
sends them again — a genuine redelivery, not a simulated one.

The decline count varies because the declined orders are a hash of the order number, and order
numbers continue from earlier runs on the same stack. With stock to spare, the storm can assert the
exact answer: every order confirms except the declined ones.

### A correction to earlier results

The failure-injection results first published here were measured with a harness that had two bugs,
found while adding the decline scenario:

- `setup` set the product's on-hand stock to the requested units, but confirmed orders keep their
  reservations, so after the first burst on a stack no units were free. Every later burst was
  rejected at the reserve step.
- `storm` counted every confirmed order for the product, not only the ones from its own run, so an
  earlier run's 100 confirmations satisfied "exactly 100 confirmed".

So on a shared stack, the scenarios after the first exercised crash recovery on the rejection path
only: orders were stranded mid-saga, but never mid-payment or mid-allocation. Both bugs are fixed —
`setup` restocks and makes warehouse room on top of what earlier runs hold, and `storm` counts only
its own run — and the table above is from the corrected harness, where every crash scenario has
orders confirming through all four services.

Fixing them exposed a third. `storm` began by marking every event order-service had ever written as
unpublished, as a built-in redelivery test. Harmless to correctness, but on a stack that had taken
~15,000 orders it put tens of thousands of re-sends ahead of each burst's own events, and the
duplicate, reorder and poison bursts could not settle within 240 s. The redelivery it was meant to
test is what the `duplicate` and `reorder` scenarios test properly, so the line is gone.

### How the system recovered

Recovery in the crash scenarios comes from the broker redelivering uncommitted messages, with the
inbox absorbing the repeats. Reconciliation is the second line, for what redelivery cannot cover.

It also had a bug that the crash scenarios could not have shown. Its scheduled entry point called
the transactional method on itself, past Spring's proxy, so in production the transaction never
opened: an order it *advanced* to match its peers was changed in memory and never saved. Its other
two repairs — re-sending a command and asking again for compensation — insert an outbox row, which
commits on its own, so they always worked. `ReconciliationJobTest` now goes in through the scheduled
path and reloads the order, and each repair is counted in `reconciliation_repairs_total{action}`.

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

- **Declines are a hash, not a processor.** The `declines` scenario drives compensation from a
  payment failure end to end, but not a processor's timeouts or late successes.
- **No latency or throughput claim.** Placement rate above is an observation on a shared laptop.
- **Single region, single Postgres, one broker node.** Nothing here says anything about partitions,
  replication or failover across availability zones.
- **No stock consumption on despatch.** Nothing decrements `on_hand` when an order ships, so
  invariant 3 is stated per order rather than as a running total — see the comment in
  `invariants.sql`.
- **Long-running reconciliation cost is unmeasured.** The job re-reads every unconfirmed order on
  each pass, including old failed ones.
