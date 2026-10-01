#!/usr/bin/env bash
# Follows one order end to end across all four services and prints its trace.
#
# Needs the observability profile:  docker compose --profile observability up -d --wait
# Then:  ./scripts/trace-order.sh            (places an order and traces it)
#        ORDERS=ORD-000123 ./scripts/trace-order.sh   (traces an order that already exists)
#
# The interesting output is a single trace containing the order's HTTP request and one
# `saga <event>` span per step, in every service the order touched.

set -uo pipefail

cd "$(dirname "$0")/.."

JAEGER=${JAEGER_URL:-http://localhost:16686}
HARNESS=./scripts/ordering-harness.py
ORDERS=${ORDERS:-1}

if ! curl -sf --max-time 5 "$JAEGER/api/services" >/dev/null 2>&1; then
  printf 'Jaeger is not reachable at %s.\nStart the observability profile first:\n\n  docker compose --profile observability up -d --wait\n\n' \
    "$JAEGER" >&2
  exit 1
fi

if [ -z "${ORDERS:-}" ] || [ "$ORDERS" = "1" ]; then
  # A SKU per run, so a previous run's reservations cannot make this one fail: resetting stock
  # below what is still reserved is refused by the database, correctly. Each run therefore leaves
  # one product behind, which is fine for a demo and obvious in the data afterwards.
  SKU="SKU-TRACE-$(date +%s)"
  "$HARNESS" setup --sku "$SKU" --units 1 >/dev/null \
    || { echo "could not create $SKU" >&2; exit 1; }
  ORDERS=$("$HARNESS" place --sku "$SKU" --quantity 1 2>/tmp/trace-order.log) || {
    echo "could not place an order; see /tmp/trace-order.log" >&2
    exit 1
  }
  if [ -z "$ORDERS" ]; then
    echo "the order was refused outright; see /tmp/trace-order.log" >&2
    exit 1
  fi
  "$HARNESS" settle --timeout 90 >/dev/null 2>&1 || echo "  (the order has not settled yet)" >&2
fi

# The trace id is the second field of the W3C traceparent the order was created with, which is
# what the consumer spans were built as children of.
TRACE=$(docker compose exec -T postgres psql -U postgres -d fulfillops -t -A \
  -c "select split_part(traceparent, '-', 2) from order_svc.outbox where order_number = '$ORDERS' order by id limit 1" \
  | tr -d ' \n')

if [ -z "$TRACE" ]; then
  printf 'no trace context recorded for %s — was it created before tracing was enabled?\n' "$ORDERS" >&2
  exit 1
fi

printf 'order   %s\ntrace   %s\nUI      %s/trace/%s\n\n' \
  "$ORDERS" "$TRACE" "$JAEGER" "$TRACE"

# Spans are batched by the exporter rather than sent one at a time, so a trace is complete a few
# seconds after the last service touched it. Poll until every expected step has arrived.
EXPECTED_STEPS=${EXPECTED_STEPS:-4}
for _ in $(seq 1 20); do
  steps=$(curl -sf --max-time 20 "$JAEGER/api/traces/$TRACE" | python3 -c "
import sys, json
traces = (json.load(sys.stdin).get('data') or [])
if traces:
    print(len([s for s in traces[0]['spans'] if s['operationName'].startswith('saga ')]))
else:
    print(0)
" 2>/dev/null || echo 0)
  if [ "${steps:-0}" -ge "$EXPECTED_STEPS" ]; then
    break
  fi
  sleep 2
done

curl -sf --max-time 20 "$JAEGER/api/traces/$TRACE" | python3 -c "
import sys, json

traces = (json.load(sys.stdin).get('data') or [])
if not traces:
    print('  trace not in Jaeger — the observability profile may not be running')
    sys.exit(1)

trace = traces[0]
services = {pid: process['serviceName'] for pid, process in trace['processes'].items()}
started = min(span['startTime'] for span in trace['spans'])

print(f\"  {len(trace['spans'])} spans across {len(set(services.values()))} services\n\")
for span in sorted(trace['spans'], key=lambda s: s['startTime']):
    offset = (span['startTime'] - started) / 1000
    print(f\"  {offset:8.1f}ms  {services.get(span['processID'], '?'):20} {span['operationName']}\")

steps = [s for s in trace['spans'] if s['operationName'].startswith('saga ')]
print()
print(f'  {len(steps)} saga step(s) linked across {len(set(services.values()))} service(s)')
sys.exit(0 if len(steps) >= $EXPECTED_STEPS else 2)
"
status=$?

if [ "$status" -ne 0 ]; then
  printf '\nthe chain is incomplete: expected %s saga steps. Note that this order succeeded, so the\nsaga ran everywhere — a short chain means spans had not been exported yet, or that a service\nis not exporting.\n' "$EXPECTED_STEPS" >&2
fi
exit "$status"