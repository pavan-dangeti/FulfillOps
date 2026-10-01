#!/usr/bin/env bash
# Runs the order load test. k6 runs in its own container, so there is nothing to install locally.
#
#   ./scripts/load-test.sh                        # 20 orders/s for 2 minutes
#   RATE=50 DURATION=5m ./scripts/load-test.sh    # a harder run
#
# The host is measured and printed with the results, because a throughput number without the
# machine it came from is not a measurement. Note that k6, the four services and Postgres all share
# that one machine, so this is an upper bound on what the stack can absorb on one host — not a
# capacity claim for any real deployment.

set -uo pipefail

cd "$(dirname "$0")/.."

RATE=${RATE:-20}
DURATION=${DURATION:-2m}
UNITS=${UNITS:-500000}
P99_BUDGET_MS=${P99_BUDGET_MS:-5000}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
COMPOSE_FILES="-f compose.yaml -f compose.observability.yaml"

if ! docker info >/dev/null 2>&1; then
  echo "docker is not running" >&2
  exit 1
fi

if ! curl -sf --max-time 5 http://localhost:8081/actuator/health >/dev/null 2>&1; then
  echo "the stack is not up. Start it first:" >&2
  echo "  SPRING_PROFILES_ACTIVE= docker compose --profile observability up -d --wait" >&2
  exit 1
fi

printf 'host\n'
printf '  %s\n' "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo 'cpu unknown')"
printf '  %s cores, %s GB RAM, docker %s\n' \
  "$(sysctl -n hw.ncpu 2>/dev/null || echo '?')" \
  "$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))" \
  "$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
printf '  k6 client, 4 Spring Boot services, Postgres and Redpanda all on this one machine\n'
# Fulfilment capacity is a hard bound: an order with no free slot fails, and the demo warehouses
# hold 1000 slots between them. At 20 orders/s a two-minute run needs 2400, so capacity is raised
# to fit the run. This is a harness provisioning step, not something the system does for itself —
# a real deployment would size capacity to expected throughput.
EXPECTED=$(( RATE * (DURATION_SECONDS=${DURATION_SECONDS:-120}) ))
CAPACITY=$(( EXPECTED + 1000 ))
docker compose $COMPOSE_FILES exec -T postgres psql -U postgres -d fulfillops -q -c \
  "update fulfilment_svc.warehouses set capacity = $CAPACITY" >/dev/null 2>&1
printf '  fulfilment capacity raised to %s slots for this run\n' "$CAPACITY"

printf '\nrun\n  %s orders/s for %s, %s units of stock, p99 budget %sms\n\n' \
  "$RATE" "$DURATION" "$UNITS" "$P99_BUDGET_MS"

docker run --rm \
  --network fulfillops_default \
  -v "$PWD/scripts:/scripts:ro" \
  -e BASE_URL=http://order-service:8081 \
  -e INVENTORY_URL=http://inventory-service:8082 \
  -e SELLER_PASSWORD="${SELLER_PASSWORD:-seller-dev-password}" \
  -e RATE="$RATE" \
  -e DURATION="$DURATION" \
  -e UNITS="$UNITS" \
  -e P99_BUDGET_MS="$P99_BUDGET_MS" \
  -e SKU="${SKU:-SKU-LOAD}" \
  "$K6_IMAGE" run --quiet /scripts/load-order.js
status=$?

echo
echo "the invariants still hold after the run:"
if ./scripts/ordering-harness.py check >/tmp/load-invariants.log 2>&1; then
  tail -1 /tmp/load-invariants.log
else
  cat /tmp/load-invariants.log
  status=1
fi

exit "$status"