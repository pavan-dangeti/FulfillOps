<div align="center">

# 📦 FulfillOps

**A distributed order system that promises stock is never oversold — and proves it.**

[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](./LICENSE)
[![CI](https://github.com/pavan-dangeti/FulfillOps/actions/workflows/ci.yml/badge.svg)](https://github.com/pavan-dangeti/FulfillOps/actions/workflows/ci.yml)

[![Angular](https://img.shields.io/badge/Angular_21-DD0031?style=flat-square&logo=angular&logoColor=white)](https://angular.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)](https://www.typescriptlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=flat-square&logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL_17-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![Redpanda](https://img.shields.io/badge/Redpanda-00AEF0?style=flat-square)](https://redpanda.com)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-5A5CE0?style=flat-square)](https://opentelemetry.io)
[![React](https://img.shields.io/badge/React_19-61DAFB?style=flat-square&logo=react&logoColor=black)](https://react.dev)
[![Three.js](https://img.shields.io/badge/Three.js-000000?style=flat-square&logo=three.js&logoColor=white)](https://threejs.org)
[![Playwright](https://img.shields.io/badge/Playwright-2EAD33?style=flat-square&logo=playwright&logoColor=white)](https://playwright.dev)
[![Pact](https://img.shields.io/badge/Pact_contracts-4A4A55?style=flat-square)](https://pact.io)

</div>

## The problem, in two sentences

An order has to hold stock, take a customer's money and claim a slot in a warehouse — three
different databases on three different services — so something will eventually go wrong halfway
through. This system makes that recoverable and then **proves** it: 1,000 orders placed
simultaneously against 100 units confirm exactly 100, and killing any service mid-order loses
nothing.

## See it running

**[▶️ 2m50s demo](docs/demo.mp4)** — the real system, filmed live. *(Hosted copy below; download
[docs/demo.mp4](docs/demo.mp4) if it does not play inline.)*

The video is the primary demo: it was recorded by a script against a running stack, so the orders on
the 3D floor, the trace in Jaeger and the metrics on the dashboard are all real and were produced as
it filmed. Recreate it with `node e2e-tests/record-demo.mjs`.

**[→ Try the 3D ops floor](https://fulfill-ops.vercel.app)** — no install required, but note it runs
in **seed mode**: the deployed demo is front-end only, with no backend behind it. The video is what
shows the real system. See [Limitations](#limitations).

---

## Results

Every number here is produced by a script in this repository. Every command is the one that produced
it.

| # | Result | Baseline | How it was measured | Reproduce |
|---|---|---|---|---|
| 1 | **300 orders for 100 units → exactly 100 reservations, 0 oversold** | **300 of 300 were told yes** — the naive check-then-write oversells 3× | 300 contenders on a 16-thread pool reserve 1 unit each against 100 on hand | `./mvnw -pl services/inventory-service -am -Dtest=ReservationsTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| 2 | **1,000 orders for 100 units → exactly 100 confirmed, 900 rejected** | **921–969 of 1,000 were told yes** across three runs — the same check-then-write with 1,000 contenders, measured at the reservation layer rather than over HTTP | 1,000 concurrent HTTP orders at 100 clients, then nine cross-store invariants; the baseline as row 1 | `./scripts/ordering-harness.py setup --units 100 && ./scripts/ordering-harness.py storm --orders 1000 --units 100` |
| 3 | **All 9 failure scenarios hold**, 300 orders stranded mid-saga in each crash | — | `SIGKILL` per service mid-burst, broker restart, 30 duplicate and 10 reordered re-deliveries, 20% of payments declined, a poison message; then the invariants | `./scripts/chaos.sh` |
| 4 | **~2,980 orders/min, end-to-end p99 1,448–1,526 ms** at 50/s offered; a third run hit a 15 s broker stall (p99 15,907 ms) | — | k6, three runs, Apple M5 / 10 cores / 16 GB, with k6 + 4 services + Postgres + Redpanda on that one host | `RATE=50 DURATION=2m ./scripts/load-test.sh` |
| 5 | **1,200 orders/min, end-to-end p99 1,118–1,172 ms** at 20/s offered | — | same host, three runs | `RATE=20 DURATION=2m ./scripts/load-test.sh` |
| 6 | **One order, 13 spans, 4 services** in ~750 ms | — | one `POST /api/orders` traced through every service it touched | `./scripts/trace-order.sh` |
| 7 | 99 Java tests, 15 Playwright tests, 9 invariants, 4 CI jobs | — | Testcontainers against real Postgres; Pact contracts; full stack rebuilt per run | `./mvnw verify` · `cd e2e-tests && npx playwright test` |

**On the baselines.** There is no public benchmark for a bespoke order saga, so the only honest
baseline is the obvious implementation of the thing being fixed. Row 1 measures it: read the counter,
check there is room, write it back — and 300 concurrent contenders for 100 units are *all* told yes,
because each thread read a stale value and decided independently. At the storm's 1,000 contenders it
is 921–969 told yes, varying run to run because it is a race; the same test prints the figure every
time. The shipped code replaces that with one conditional `UPDATE`, where the row lock makes a
competing writer re-check against the committed value, and a `CHECK` constraint behind it. Row 2's
baseline is measured at the reservation layer, not through the HTTP harness: reproducing it end to
end would mean shipping a code path that oversells on purpose. Rows 3–6 have no meaningful baseline
and are reported without one.

**What "end-to-end" means in rows 4 and 5**, since it is the only figure worth quoting: `POST
/api/orders` returns 202 the moment the order and its reserve command are committed — the order is
not fulfilled then. The number is from the client's POST to its first observation of a terminal
`sagaStep`, polled every 200 ms, so that granularity is up to 200 ms of every figure and flatters the
p99 slightly. The bare POST is 225–300 ms p99 at 50/s.

Full detail, including every failure scenario, a correction to the first published chaos results,
the stalled load-test run and the memory measurements:
[`docs/ordering-invariants.md`](docs/ordering-invariants.md) and [`docs/performance.md`](docs/performance.md).

---

## Architecture

```
                        seller-dashboard (Angular 21)
                        cs-console (Spring Boot + Thymeleaf)
                        ops-floor (React + Three.js)
                                   │  RS256 JWT, verified against order-service's JWKS
                                   ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │                          order-service  :8081                              │
  │   orders · saga_step · the saga · reconciliation · token issuance          │
  └────────────────────────────────────────────────────────────────────────────┘
         │                    │                    │                    │
         │ order.reserve      │ inventory.reserved │ payment.charged      │ fulfilment.allocated
         │   .requested        │                    │                    │
         ▼                    ▼                    ▼                    ▼
  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
  │  inventory   │   │   payment    │   │ fulfilment   │   │  cs-console  │
  │   :8082      │   │    :8083     │   │    :8084     │   │    :8080     │
  │ products     │   │ payments     │   │ warehouses   │   │ (no data)    │
  │ reservations │   │ unique by    │   │ allocations  │   └──────────────┘
  │ CHECK        │   │ order_number │   │ CHECK        │
  │ reserved ≤   │   └──────────────┘   │ allocated ≤  │
  │ on_hand      │                      │ capacity     │  ──► /api/ops (public, read-only)
  └──────────────┘                      └──────────────┘        to the 3D ops floor
         │                    │                    │
         └────────────────────┴────────────────────┘
                              ▲
        ┌─────────────────────┴──────────────────────┐
        │  Redpanda · one topic, keyed by order      │  outbox → relay → topic → inbox → handler
        └─────────────────────┬──────────────────────┘  every event at-least-once, applied once
                              │
   ┌──────────────────────────┴───────────────────────────┐
   │  one Postgres instance · one schema and login role  │  a role cannot read another's tables
   │  per service · checked by scripts/check-schema-isolation.sh
   └──────────────────────────────────────────────────────┘

  Observability (--profile observability): Jaeger · Prometheus · Grafana
```

**The order saga.** `POST /api/orders` commits the order *and* the command to reserve its stock in one
transaction, then the work happens over Kafka: inventory holds the stock, payment takes the money,
fulfilment claims a slot, order-service confirms. A refused step fails the order, broadcasts one
compensation request, and each service undoes its own effect.

Nothing above is a distributed transaction. The guarantees come from a single-database transaction per
step, at-least-once delivery, and effects that are idempotent — which yields the appearance of
exactly-once without ever claiming it. [`docs/design.md`](docs/design.md) states the consistency
model precisely.

---

## Running it

```bash
cp .env.example .env
docker compose up --build --wait          # Postgres, Redpanda, 4 services, CS console

cd seller-dashboard && npm install && npm start   # :4200, proxies /api to the services
cd ops-floor && npm install && VITE_SOURCE=live npm run dev   # :5174, live from the real system
```

Requires Docker with Compose v2 and Node 22. JDK 21 only to run Maven outside Docker (`./mvnw` is
bundled). Accounts from `.env.example`: seller `seller`/`seller-dev-password`, CS
`cs`/`cs-dev-password`.

To add the observability stack — traces, metrics, the dashboard on <http://localhost:3000>:

```bash
docker compose --profile observability up -d --wait
```

---

## Tests

| Suite | Command |
|---|---|
| Services and CS console — 99 tests | `./mvnw verify` |
| Seller dashboard — build, consumer contracts, formatting | `cd seller-dashboard && npx ng build && npx ng test --watch=false && npm run format:check` |
| End to end — 15 tests, full stack per run | `cd e2e-tests && npx playwright test` |
| Ordering invariants — the 1,000-order claim | `./scripts/ordering-harness.py storm --orders 1000` |
| Failure injection | `./scripts/chaos.sh` |
| Load test | `./scripts/load-test.sh` |
| One order's trace | `./scripts/trace-order.sh` |
| Schema isolation | `scripts/check-schema-isolation.sh` |

CI runs the first four on every push, plus failure injection on demand:

```bash
gh workflow run ci --ref main -f chaos=true
```

---

## Limitations

Stated plainly, because a limitation you can name is better than one a reviewer finds.

**The live demo is front-end only.** <https://fulfill-ops.vercel.app> runs the 3D ops floor in seed
mode with no backend behind it. The video and the scripts are what show the real system. The
constraint is compute, not storage: free Postgres exists, but five JVMs, Postgres and Redpanda need
a few gigabytes of memory on one always-on host, which no free platform tier offers.

**Declines are simulated, and nothing pages anyone.** A configured share of payments declines,
decided by a hash of the order number, which exercises the compensation path but not the timeouts
and late successes of a real processor. Dead letters and a stalled outbox raise Prometheus alerts,
but no Alertmanager is configured, so the alerts are visible and go nowhere.

**Nothing consumes stock on despatch.** A shipped order still counts as holding its reservation, so
the oversell invariant is stated per order rather than as a running total. See
[`docs/design.md`](docs/design.md#known-modelling-gap).

**The Ops Floor's seller plane is seed-backed.** Sellers and CS read the same order store now, so a
live seller plane would only mirror the CS plane. The CS plane is live and shows real saga state;
the HUD says which is which.

**The relay is the throughput ceiling.** One thread, one blocking send per event, one batch per tick.
Publication is bounded by batch size over the tick interval. More relay instances are safe
(`FOR UPDATE SKIP LOCKED` keeps them off each other's rows) but that is untested at scale.

**Reconciliation is unbounded.** It re-reads every unconfirmed order each pass, including old failed
ones, and re-announces a still-incomplete one every pass. Safe because every consumer is idempotent,
unbounded in cost.

**One host, one Postgres, one broker node.** No partitions, no replication, no multi-region, no
failover. One broker is a single point of stall as well as of failure: in one of six load-test runs
every service's sends to it timed out together for about 15 seconds. Nothing was lost, but nothing
moved. The oversell guarantee is enforced by the database and reproduced on a second machine, but
the throughput numbers are a property of one laptop.

**Tracing was sampled at 100%** during the load test, so the exporter's cost is *inside* those
numbers. Lower `TRACING_SAMPLE_RATE` and re-measure before quoting capacity.

**Warehouse capacity is a migration, not configuration.** The load test has to patch it before a run,
because it is a real ceiling: the first 20 orders/s run failed 219 of 1,200 orders on slots with
500,000 units of stock available. The system behaved correctly — no slot means fail, refund, release
— but a deployment that does not size capacity gets a rising failure rate rather than a rising queue.

**The signing key is generated at startup,** so order-service cannot run as several replicas and a
restart invalidates issued tokens. Sign-in throttling is per-instance and in memory.

---

## Documentation

- [`docs/design.md`](docs/design.md) — context, goals, non-goals, the consistency model, failure
  handling, alternatives considered, trade-offs, risks
- [`docs/ordering-invariants.md`](docs/ordering-invariants.md) — the nine invariants and the failure
  injection results
- [`docs/performance.md`](docs/performance.md) — throughput, latency, tracing, metrics
- [`TRADEOFFS.md`](TRADEOFFS.md) — the smaller decisions and why each went the way it did
- [`contracts/pacts/`](contracts/pacts/) — consumer-driven contracts, verified by each provider

## Project layout

```
├── services/            # order, inventory, payment and fulfilment (Spring Boot 4, Java 21)
├── common/              # shared JWT resource-server config, API errors, transactional messaging
├── cs-console/          # Spring Boot + Thymeleaf console for CS reps
├── seller-dashboard/    # Angular 21 SPA for sellers
├── ops-floor/           # Vite + React + Three.js live view of the order domain
├── contracts/pacts/     # consumer-driven contracts, generated by consumer tests, committed
├── e2e-tests/           # Playwright suite, and the demo recorder
├── deploy/              # Postgres roles and schemas; observability provisioning
├── scripts/             # invariants, ordering harness, chaos, load test, trace
└── compose.yaml         # the whole stack; --profile observability adds Jaeger, Prometheus, Grafana
```

## License

MIT — see [LICENSE](./LICENSE).