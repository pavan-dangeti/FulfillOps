# Performance and observability

Three things this phase adds, each with the command that reproduces it and the host it was measured on.

---

## Running it

```bash
SPRING_PROFILES_ACTIVE= docker compose --profile observability up -d --build --wait

./scripts/load-test.sh                       # 20 orders/s for 2 minutes
RATE=50 DURATION=2m ./scripts/load-test.sh   # a harder run
./scripts/trace-order.sh                     # follow one order across all four services
```

The observability profile adds three containers that are not part of the ordinary stack:

| Service | URL | What it is for |
|---|---|---|
| Jaeger | http://localhost:16686 | Traces |
| Prometheus | http://localhost:9090 | Metrics |
| Grafana | http://localhost:3000 | The provisioned **FulfillOps — order saga** dashboard |

Grafana runs anonymous and read-only. That is fine for a local demo container holding no data
worth protecting, and would not be acceptable on a network anyone else can reach.

---

## Throughput and latency

**Host.** Apple M5, 10 cores, 16 GB RAM, Docker 29.6.1. The k6 client, all four Spring Boot
services, Postgres and Redpanda run on this single machine, so these are an upper bound on what
one host absorbs — not a capacity claim for any deployment. k6 runs in its own container
(`grafana/k6`), so nothing has to be installed.

Three runs at each rate, one after another on the same stack, with the memory settings from the
[Memory](#memory) section and tracing exported to Jaeger at 100%:

| Offered | Run | Reached a terminal state | Accepted p50/p95/p99 | **End-to-end p50/p95/p99** | Saga failures | Invariants |
|---|---|---|---|---|---|---|
| 20/s for 2 min | 1 | 2,400 | 22 / 45 / 75 ms | **718 / 1,049 / 1,172 ms** | 0 | hold |
| | 2 | 2,401 | 22 / 43 / 55 ms | **868 / 966 / 1,118 ms** | 0 | hold |
| | 3 | 2,401 | 22 / 44 / 67 ms | **886 / 1,102 / 1,160 ms** | 0 | hold |
| 50/s for 2 min | 1 | 5,971 | 47 / 109 / 225 ms | **982 / 1,265 / 1,448 ms** | 0 | hold |
| | 2 | 5,955 | 48 / 115 / 276 ms | **1,010 / 1,351 / 1,526 ms** | 0 | hold |
| | 3 | 5,245 | 53 / 123 / 300 ms | **1,103 / 1,415 / 15,907 ms** | 0 | hold |

So about 1,200 orders a minute at 20/s and about 2,980 a minute at 50/s complete end to end, with a
p99 near 1.1 s and 1.5 s — except the last run.

**The last run stalled, and it is reported rather than dropped.** For about 15 seconds every one of
the four relays stopped publishing at the same moment, and their sends to the broker timed out
(`Could not publish … TimeoutException`, after the relay's 10 s send timeout). Nothing was lost: the
failed batch rolled back and was sent again, every order reached a terminal state afterwards, and
the invariants held. But for those seconds no order moved, which is the 15.9 s p99 and the ~700
orders that had not finished inside k6's window. Four services that share nothing but the broker
stalling together points at the broker. Its memory stayed at a quarter of its limit and it logged
nothing above INFO; the suspicion is a slow fsync on Docker Desktop's virtual disk, which is
untested. An earlier 13.7 s run had the same shape. One broker node is a single point of stall as
well as of failure, and this is what that looks like.

```bash
SPRING_PROFILES_ACTIVE= docker compose --profile observability up -d --build --wait
for i in 1 2 3; do RATE=20 DURATION=2m ./scripts/load-test.sh; done
for i in 1 2 3; do RATE=50 DURATION=2m ./scripts/load-test.sh; done
```

**How "end to end" is measured, because it is the only number worth quoting.** `POST /api/orders`
returns 202 the moment the order and its reserve command are committed — the order is not
fulfilled then. The saga still has to reserve stock, take payment and claim a slot. So the figure
above is from the client's POST to its first observation of a terminal `sagaStep`, polled every
200 ms. That polling granularity is up to 200 ms of every reported figure, which flatters the p99
slightly and is stated rather than buried. `accepted`, the bare POST, is reported beside it for
comparison.

**Reading the numbers.** In the runs that did not stall, throughput rose 2.5× while the end-to-end
p99 rose about 30% (1,118–1,172 ms to 1,448–1,526 ms), which is the shape you want: queueing, not
collapse. The floor is structural — three saga hops at the relay's
200 ms tick is ~600 ms before any work is done. Under a sustained burst those ticks align into a
queue and the p99 climbs; the accepted p99 barely moves, because accepting an order is one
transaction and does not wait on the broker.

**Not measured, deliberately.** This run gives the product 500,000 units so no order is refused,
because the question is throughput. The oversubscribed case is
[`scripts/ordering-harness.py storm`](ordering-invariants.md), which is a correctness test rather
than a load test.

### Fulfilment capacity is a hard bound, and it showed up

The first 20/s run had **219 of 1,200 orders fail the saga** with plenty of stock left. Warehouse
capacity is the cause: the two demo warehouses hold 1,000 slots between them, and 981 were free.

That is the system behaving correctly — an order with no free slot cannot be fulfilled, so it is
failed, refunded and released. It is also a real ceiling worth naming, and the load test now raises
capacity to fit the run before starting. A production deployment would size capacity to expected
throughput rather than have a test harness patch it.

---

## Memory

**Question.** Each service ran under a 512 MiB container limit and inventory-service was seen at
507.6 MiB. Was that heap, class metadata or native memory, was anything killed for it, and what
should the limits be?

**Method.** `scripts/memory-profile.sh` reads each container's cgroup (limit, current, peak,
anonymous memory, how many times it reached the limit and the kernel had to reclaim), Docker's OOM
events and restart count, and the JVM's own breakdown from native memory tracking, attached from a
JDK sidecar because the runtime image has no `jcmd`. Every run below is a fresh stack with tracking
on (`JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary`), a 1,000-order storm, then three k6 runs
at 50 orders/s for 2 minutes.

```bash
JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary SPRING_PROFILES_ACTIVE= docker compose up -d --build --wait
since=$(date +%s)
./scripts/ordering-harness.py setup --units 100 && ./scripts/ordering-harness.py storm --orders 1000 --units 100
for i in 1 2 3; do RATE=50 DURATION=2m ./scripts/load-test.sh; done
./scripts/memory-profile.sh "$since"
```

**Where the memory went** — order-service after the k6 runs, before tuning, in MB:

| Heap | Class metadata, metaspace, symbols | Code cache | Threads | Other JVM | Untracked native |
|---|---|---|---|---|---|
| 155 | 188 | 83 | 23 | 26 | 16–93 across services |

So it was not the heap. Class metadata is the largest single cost of a Spring Boot service with
Hibernate, Kafka and OpenTelemetry on the classpath, and the code cache grows as the JIT compiles
the hot paths. "Untracked" is anonymous memory the JVM does not account for, mostly the C
allocator's per-thread arenas; it is approximate, and can read slightly negative because tracking
counts committed pages that are not yet resident.

The real problem was the heap's ceiling, not its size. `-XX:MaxRAMPercentage=75` let the heap grow
to 384 MB, beside roughly 350–400 MB of everything else, in a 512 MB container. It had not grown that
far, so nothing had been killed — but order-service was living at its limit.

**Before and after.** The change: a 768 MiB limit for the four saga services (cs-console stays at
512 MiB; it peaks near 230), `-XX:MaxRAMPercentage=33`, and `MALLOC_ARENA_MAX=2`.

| After storm + 3 × k6 | Before: 512 MiB, heap 75% | After: 768 MiB, heap 33%, 2 arenas |
|---|---|---|
| order-service peak / times at the limit | 512 / **2,297** | 558 / **0** |
| inventory-service peak / times at the limit | 512 / 104 | 454 / 0 |
| payment-service, fulfilment-service peak | 442, 444 | 386, 386 |
| OOM kills, restarts | 0, 0 | 0, 0 |

Neither configuration was ever killed. Before, the kernel was reclaiming from order-service
thousands of times in a few minutes to keep it under its limit; after, no service reached its limit
in the storm, the k6 runs or the full failure-injection suite.

**The worst case, forced.** A heap that has not grown yet proves nothing about one that has, so one
run started every service with its heap fully committed and touched
(`-XX:+AlwaysPreTouch -XX:InitialRAMPercentage=33`). order-service peaked at 644 of 768 MiB after
the k6 run and 621 after all nine failure scenarios, and never reached the limit. A maxed-out heap
fits with about 120 MiB to spare.

**Latency did not measurably change.** Three k6 runs on each side, same host:

| | Before | After |
|---|---|---|
| End-to-end p99 (ms) | 4,351 · 2,473 · 2,669 | 4,548 · 2,685 · 2,427 |
| Accepted p99 (ms) | 586 · 253 · 962 | 306 · 253 · 276 |

The end-to-end figures overlap completely. The accepted (POST) p99 was steadier after tuning, but
three runs is not enough to call that an effect, so it is reported as an observation. An earlier
single before-tuning run measured 13,687 ms end to end; the repeated runs show it was an outlier, and
it is why every figure here is given as all three runs rather than one.

**Cost.** The four saga services' limits rise from 2 GiB to 3 GiB in total. Their actual use at idle
fell, from roughly 400–450 MB each to 295–350 MB, because of the arena cap.

---

## How big a machine it needs

**Method.** `scripts/measure-host.sh` builds and runs the committed tree inside one Docker-in-Docker
container capped at a CPU count and a memory limit — what a Codespace or any single host looks like
from the inside — and samples that container's memory once a second. The build is cold: no Maven
cache, no images. Memory includes reclaimable page cache, so the peaks are upper bounds.

```bash
CPUS=2 MEMORY=8g ./scripts/measure-host.sh
```

| Phase, on 2 CPUs and 8 GB | Time | Peak memory |
|---|---|---|
| Cold build of every image | 321 s | 3,392 MB |
| Cold start, observability included, until every healthcheck passes | 213 s | 4,160 MB |
| 1,000-order storm, then every invariant | 35 s | 4,163 MB |
| Idle, a minute later | — | 4,046 MB |

The dev container itself — Docker, the whole stack and both front-end dev servers, everything a
Codespace runs except the editor — used **3.65 GiB** after `./scripts/up.sh` and a 1,000-order storm
(`docker stats` on the container built by `npx @devcontainers/cli up --workspace-folder .`). So the
smallest Codespace, 2 cores and 8 GB, is enough, and it is what `.devcontainer/devcontainer.json`
asks for: allowances are counted in core-hours, so a visitor gets twice the time on 2 cores as on 4.

---

## Tracing one order end to end

```bash
./scripts/trace-order.sh
```

places an order and prints its trace:

```
order   ORD-000019
trace   448875aebc3e70feb617a0a36243feb3
UI      http://localhost:16686/trace/448875aebc3e70feb617a0a36243feb3

  13 spans across 4 services

       0.0ms  order-service        http post /api/orders
     147.1ms  inventory-service    saga order.reserve.requested
     349.6ms  order-service        saga inventory.reserved
     349.7ms  payment-service      saga inventory.reserved
     558.4ms  order-service        saga payment.charged
     559.2ms  fulfilment-service   saga payment.charged
     758.4ms  order-service        saga fulfilment.allocated
```

**How the chain is carried.** The relay usually publishes on a later tick from a different request
than the one that made the change, so the producer's trace cannot be picked up from whatever
happens to be current at send time. `Outbox` therefore captures the W3C `traceparent` into the
outbox row at write time, and the event carries it in its envelope. `EventDispatcher` reads it and
starts its span as a child, so one order reads as one trace rather than four unrelated ones.

**Why the span is built in the dispatcher and not left to the framework.** spring-kafka's
record-level observation did not produce consumer spans here, and tracing silently is worse than not
tracing: the outbox kept working, the metrics looked fine, and there was simply no trace. Doing it
explicitly puts the span boundary where the transaction boundary already is and keeps it working
regardless of container configuration.

One sharp edge, since it cost real time: `Tracer.withSpan(span).close()` only restores the previous
context — it does **not** finish the span, and an unfinished span is never exported. The explicit
`span.end()` is what makes the chain appear.

---

## Metrics

`/actuator/prometheus` on each service, scraped every 5 s and labelled `application`, so one set of
dashboard queries serves all four.

Three of the gauges exist because nothing *fails* when the messaging layer is unhealthy — events
just accumulate, silently:

| Metric | What a rising value means |
|---|---|
| `outbox_unpublished` | The relay cannot keep up |
| `inbox_processed_total` | Per service. One that stops moving while the others climb is the stuck consumer |
| `outbox_relayed_total` | Throughput the relay is achieving |
| `messaging_dead_lettered_total` | Per service. Any increase is an event a handler could not process, now on `fulfillops.dlt` |
| `reconciliation_repairs_total{action}` | Orders reconciliation put right — `advanced`, `resumed` or `recompensated`. Normally flat |

Two Prometheus alert rules, in `deploy/observability/alerts.yml`, watch the signals that mean an
order may be stuck: `EventsDeadLettered` and `OutboxNotDraining`. They fire on
<http://localhost:9090/alerts>; no Alertmanager is configured, so they page nobody.

The dashboard also covers order acceptance rate and p99, HTTP latency by service, Kafka consumer
lag, Postgres connections in use and JVM heap.

**One exposure change worth naming.** `/actuator/prometheus` is now permitted without
authentication, alongside health, because the Prometheus scrape model has no credential to present.
Only `health`, `info` and `prometheus` are exposed, the services bind to `127.0.0.1`, and a public
deployment should still keep them behind the network.

---

## What this does not prove

- **No multi-node or multi-region claim.** One machine, one Postgres, one broker node, one replica
  of everything. Nothing here speaks to partitions, replication or failover across zones.
- **Tracing cost is not measured.** `TRACING_SAMPLE_RATE` is set to 1.0 so a demo shows every
  order; at that rate the exporter is doing real work during the load test, so the throughput
  numbers include it. Lower the rate and re-measure before quoting capacity.
- **JVM heap and GC are uninstrumented in the dashboard.** A latency tail caused by a stop-the-world
  collection would not be distinguishable from broker queueing here.
- **Alerts go nowhere.** The two rules fire in Prometheus, but nothing routes them to a person.
- **No slow-query analysis.** Postgres is clearly the shared bottleneck at these rates, but nothing
  here attributes latency to specific statements.