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

| Offered | Offered/min | Accepted p50/p95/p99 | **End-to-end p50/p95/p99** | Saga failures | Invariants |
|---|---|---|---|---|---|
| 20/s for 2 min | 1,200/min | 21 / 44 / 77 ms | **853 / 1,140 / 2,609 ms** | 0 | hold |
| 50/s for 2 min | 3,000/min | 58 / 147 / 311 ms | **1,132 / 1,664 / 3,795 ms** | 0 | hold |

```bash
RATE=20 DURATION=2m ./scripts/load-test.sh
RATE=50 DURATION=2m P99_BUDGET_MS=8000 ./scripts/load-test.sh
```

**How "end to end" is measured, because it is the only number worth quoting.** `POST /api/orders`
returns 202 the moment the order and its reserve command are committed — the order is not
fulfilled then. The saga still has to reserve stock, take payment and claim a slot. So the figure
above is from the client's POST to its first observation of a terminal `sagaStep`, polled every
200 ms. That polling granularity is up to 200 ms of every reported figure, which flatters the p99
slightly and is stated rather than buried. `accepted`, the bare POST, is reported beside it for
comparison.

**Reading the numbers.** Throughput rose 2.5× and end-to-end latency rose about 45%, which is the
shape you want: queueing, not collapse. The floor is structural — three saga hops at the relay's
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
- **The Grafana dashboard is provisioned, not hand-built,** so it has no alerting rules and no
  recorded baselines.
- **No slow-query analysis.** Postgres is clearly the shared bottleneck at these rates, but nothing
  here attributes latency to specific statements.