// Load test for the order path. Run with scripts/load-test.sh, which wraps it in the k6 container so
// there is nothing to install.
//
// What is measured, and why it is not just the POST latency:
//
//   POST /api/orders returns 202 the moment the order and its reserve command are committed. The
//   order is not fulfilled at that point — the saga still has to reserve stock, take payment and
//   claim a slot. So the number that matters is how long an order takes to reach a terminal state,
//   which means the client has to keep asking until it does.
//
//   `end_to_end` is that whole thing: POST to first observed CONFIRMED or FAILED, polled every
//   200ms. The polling granularity is therefore up to 200ms of the reported figure, which is stated
//   here rather than buried: it flatters the p99 slightly. `accepted` is the bare POST, for
//   comparison.
//
// Deliberately not measured here: contention. This run gives the product enough stock that no order
// is ever refused, because the question here is throughput. The oversubscribed case is what
// scripts/ordering-harness.py storm covers, and it is a correctness test rather than a load test.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8081';
// The catalogue lives in a different service on a different host, so it needs its own address.
// Deriving it from BASE by swapping the port would give order-service:8082, which is nothing.
const INVENTORY_BASE = __ENV.INVENTORY_URL || BASE.replace(/^http:\/\/([^:/]+):.*/, 'http://$1:8082');
const SKU = __ENV.SKU || 'SKU-LOAD';
const UNITS = Number(__ENV.UNITS || 500000);
const POLL_INTERVAL = Number(__ENV.POLL_INTERVAL_MS || 200);
const POLL_DEADLINE = Number(__ENV.POLL_DEADLINE_MS || 60000);

const accepted = new Trend('accepted_ms', true);
const endToEnd = new Trend('end_to_end_ms', true);
const completed = new Counter('orders_completed');
const failedSaga = new Counter('orders_failed');
const errors = new Rate('request_errors');

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20), // orders offered per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Number(__ENV.VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 400),
    },
  },
  // Thresholds are the pass/fail contract for a run, so they are stated once, here.
  // k6 summarises only p(90)/p(95)/p(99) by default, which would make p50 and p99 read as n/a.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    end_to_end_ms: [`p(99)<${Number(__ENV.P99_BUDGET_MS || 5000)}`],
    request_errors: ['rate<0.01'],
  },
};

export function setup() {
  const token = http.post(
    `${BASE}/api/auth/token`,
    JSON.stringify({ username: __ENV.SELLER_USER || 'seller', password: __ENV.SELLER_PASSWORD || 'seller-dev-password' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (token.status !== 200) {
    throw new Error(`sign-in failed: ${token.status} ${token.body}`);
  }
  const auth = token.json('accessToken');
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${auth}` };

  // Enough stock that no order is refused, so the run measures throughput rather than contention.
  const created = http.post(
    `${INVENTORY_BASE}/api/products`,
    JSON.stringify({ sku: SKU, name: 'Load Widget', unitPrice: '10.00', stock: UNITS }),
    { headers },
  );
  if (created.status !== 201 && created.status !== 409) {
    throw new Error(`could not create ${SKU}: ${created.status} ${created.body}`);
  }
  if (created.status === 409) {
    const reset = http.put(
      `${INVENTORY_BASE}/api/products/${SKU}/stock`,
      JSON.stringify({ stock: UNITS }),
      { headers },
    );
    if (reset.status !== 200) {
      throw new Error(`could not reset stock on ${SKU}: ${reset.status} ${reset.body}`);
    }
  }
  return { auth };
}

export default function (data) {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${data.auth}` };

  const posted = http.post(
    `${BASE}/api/orders`,
    JSON.stringify({
      customerName: 'Load Test',
      email: 'load@example.com',
      sku: SKU,
      quantity: 1,
    }),
    { headers },
  );
  accepted.add(posted.timings.duration);

  if (posted.status !== 202) {
    errors.add(true);
    return;
  }
  errors.add(false);

  const orderNumber = posted.json('orderNumber');
  if (!orderNumber) {
    return;
  }

  // Poll until the saga reaches a terminal state. A 200 with a terminal sagaStep means the order
  // is settled; anything else means keep asking.
  const started = Date.now();
  let settled = false;
  while (Date.now() - started < POLL_DEADLINE) {
    const polled = http.get(`${BASE}/api/orders/${orderNumber}`, { headers });
    const step = polled.json('sagaStep');
    if (polled.status === 200 && (step === 'CONFIRMED' || step === 'FAILED')) {
      settled = true;
      if (step === 'CONFIRMED') {
        completed.add(1);
      } else {
        failedSaga.add(1);
      }
      break;
    }
    sleep(POLL_INTERVAL);
  }

  if (settled) {
    endToEnd.add(Date.now() - started);
  }
  check(null, { 'order settled': () => settled });
}

function sleep(ms) {
  // k6 has no sleep import in all builds; a busy wait on a short interval is adequate and keeps
  // this file dependency-free.
  const until = Date.now() + ms;
  while (Date.now() < until) {
    /* wait */
  }
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const orders = Number(__ENV.RATE || 20);
  const minutes = (data.state.testRunDurationMs || 0) / 60000;

  const line = (label, value) => console.log(`  ${label.padEnd(34)} ${value}`);
  console.log('\nload test summary');
  console.log(`  ${'orders offered'.padEnd(34)} ${orders}/s for ${(minutes).toFixed(1)} min`);
  line('accepted p50 / p95 / p99 (ms)', p(metrics, 'accepted_ms'));
  line('end to end p50 / p95 / p99 (ms)', p(metrics, 'end_to_end_ms'));
  line('orders reaching a terminal state', metrics.orders_completed ? metrics.orders_completed.values.count : 0);
  line('of those, failed by the saga', metrics.orders_failed ? metrics.orders_failed.values.count : 0);
  line('request error rate', metrics.request_errors ? metrics.request_errors.values.rate.toFixed(4) : 'n/a');
  line('achieved rate (req/s, all calls)', (metrics.http_reqs ? metrics.http_reqs.values.rate : 0).toFixed(1));

  const failed = metrics.end_to_end_ms && metrics.end_to_end_ms.thresholds
    ? Object.values(metrics.end_to_end_ms.thresholds).some((t) => !t.ok)
    : false;
  return {
    stdout: `\n${failed ? 'THRESHOLD NOT MET' : 'thresholds met'}\n`,
    'load-test-summary.json': JSON.stringify(data.metrics, null, 2),
  };
}

function p(metrics, name) {
  const m = metrics[name];
  if (!m || !m.values) {
    return 'n/a';
  }
  // A run with no samples has metrics but no quantiles, so each is checked rather than the set.
  const parts = [0.5, 0.95, 0.99].map((q) => {
    const value = m.values[`p(${q * 100})`];
    return typeof value === 'number' ? value.toFixed(0) : 'n/a';
  });
  return parts.join(' / ');
}