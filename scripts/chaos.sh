#!/usr/bin/env bash
# Failure injection: break something in the middle of a burst of orders, let the system
# put itself right, and then check every invariant.
#
#   ./scripts/chaos.sh                 # every scenario, in turn
#   ./scripts/chaos.sh inventory       # just one
#
# Each scenario fires a burst in the background, does the damage while it is in flight, then
# waits for the system to converge and runs the invariant checks. Convergence is the point: a
# service that is killed mid-saga leaves orders stranded, and it is the outbox plus reconciliation
# that has to notice and finish or undo them. A scenario only passes if the system gets there on
# its own, without the database being touched.

set -uo pipefail

cd "$(dirname "$0")/.."

ORDERS=${ORDERS:-300}
UNITS=${UNITS:-100}
CONCURRENCY=${CONCURRENCY:-50}
# How long after the burst starts the damage lands. The burst is placed in a couple of seconds and
# the saga takes a few more, so this lands while messages are still moving.
KILL_AFTER=${KILL_AFTER:-1}
# Long enough for reconciliation to notice a stranded order: it runs on a 60s timer and only
# considers orders older than its 30s grace period.
SETTLE_TIMEOUT=${SETTLE_TIMEOUT:-240}

HARNESS=./scripts/ordering-harness.py
SKU=SKU-STORM

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=1; }
note() { printf '        %s\n' "$1"; }

FAILED=0

reset_state() {
  "$HARNESS" setup --sku "$SKU" --units "$UNITS" >/dev/null || { fail "could not reset state"; exit 1; }
}

# Fires a burst without waiting for it to settle, so the damage can land mid-flight.
burst() {
  touch /tmp/chaos-watching
  watch_stranded &
  WATCH_PID=$!
  "$HARNESS" storm --orders "$ORDERS" --units "$UNITS" --quantity 1 --sku "$SKU" \
    --concurrency "$CONCURRENCY" --settle-timeout "$SETTLE_TIMEOUT" >"/tmp/chaos-burst.log" 2>&1 &
  BURST_PID=$!
}

stop_watching() {
  rm -f /tmp/chaos-watching
  wait "$WATCH_PID" 2>/dev/null
}

wait_for_burst() {
  wait "$BURST_PID"
  return $?
}

# Kills a service the way a crash does: no signal handler, no flush, nothing.
crash_service() {
  local service=$1
  note "SIGKILL $service mid-saga"
  docker compose kill -s SIGKILL "$service" >/dev/null 2>&1
  sleep 3
  note "starting $service again"
  docker compose start "$service" >/dev/null 2>&1
  # The outbox relay or listener needs a moment to reconnect to the broker and the database.
  sleep 5
}

# Watches how many orders are stranded at each moment, so a scenario cannot pass by being
# vacuous: if the peak stays at zero then the damage never actually landed mid-saga and the
# result means nothing. Sampled from the database rather than the API so it keeps working while
# the service under test is down.
watch_stranded() {
  : >/tmp/chaos-stranded.log
  while [ -e /tmp/chaos-watching ]; do
    docker compose exec -T postgres psql -U postgres -d fulfillops -t -A \
      -c "select count(*) from order_svc.orders where saga_step not in ('CONFIRMED','FAILED')" \
      >>/tmp/chaos-stranded.log 2>/dev/null
    sleep 0.3
  done
}

peak_stranded() {
  sort -rn /tmp/chaos-stranded.log 2>/dev/null | head -1 | tr -d ' \n'
}

# The system's own account of the repair. If reconciliation did nothing, the run only proves
# that messages were retried, which is a weaker claim and should be visible in the output.
reconciliation_work() {
  docker compose logs order-service --no-color --since 10m 2>/dev/null \
    | grep -oE 'reconciliation examined [0-9]+ unsettled order\(s\): advanced [0-9]+, resumed [0-9]+, recompensated [0-9]+' \
    | tail -3
}

report_recovery() {
  local label=$1 peak
  peak=$(peak_stranded)
  peak=${peak:-0}
  if [ "$peak" -gt 0 ]; then
    note "peak stranded mid-saga: $peak order(s) — the damage did land in flight"
  else
    note "peak stranded mid-saga: 0 — the damage landed outside the saga, so treat this as a weak result"
  fi
  local repairs
  repairs=$(reconciliation_work)
  if [ -n "$repairs" ]; then
    note "reconciliation reported: $repairs"
  else
    note "reconciliation had nothing to repair; recovery was the broker redelivering"
  fi
}

# --- scenarios ----------------------------------------------------------------

scenario_kill_service() {
  local service=$1 label=${2:-$1}
  printf '\n%s\n' "--- kill $service mid-saga ---"
  reset_state
  burst
  sleep "$KILL_AFTER"
  crash_service "$service"
  local outcome=0
  wait_for_burst || outcome=1
  stop_watching
  report_recovery "$label"
  if [ "$outcome" -eq 0 ]; then
    pass "$label: the burst finished and every invariant held"
  else
    fail "$label: $(tail -3 /tmp/chaos-burst.log | tr '\n' ' ')"
  fi
}

scenario_restart_broker() {
  printf '\n%s\n' "--- restart the broker mid-saga ---"
  reset_state
  burst
  sleep "$KILL_AFTER"
  note "restarting redpanda"
  docker compose restart redpanda >/dev/null 2>&1
  sleep 8
  local outcome=0
  wait_for_burst || outcome=1
  stop_watching
  report_recovery "broker restart"
  if [ "$outcome" -eq 0 ]; then
    pass "broker restart: the burst finished and every invariant held"
  else
    fail "broker restart: $(tail -3 /tmp/chaos-burst.log | tr '\n' ' ')"
  fi
}

# Re-publishes a settled order's events, so the consumers see the same work twice.
scenario_duplicate() {
  printf '\n%s\n' "--- duplicate delivery ---"
  reset_state
  if ! "$HARNESS" storm --orders "$ORDERS" --units "$UNITS" --quantity 1 --sku "$SKU" \
       --concurrency "$CONCURRENCY" --settle-timeout "$SETTLE_TIMEOUT" >/tmp/chaos-burst.log 2>&1; then
    fail "duplicate delivery: the baseline burst did not pass"
    return
  fi
  # Pick orders that really completed, so a second look has something to get wrong.
  settled_orders() {
    docker compose exec -T postgres psql -U postgres -d fulfillops -t -A \
      -c "select order_number from order_svc.orders where saga_step = 'CONFIRMED' limit 10"
  }
  local redelivered=0 orders=0 order
  for order in $(settled_orders); do
    "$HARNESS" inject duplicate --order "$order" --count 3 >/dev/null 2>&1 \
      && redelivered=$((redelivered + 3)) && orders=$((orders + 1))
  done
  note "re-queued $redelivered event(s) for $orders confirmed order(s)"
  # Let the relay deliver the duplicates and the consumers react (or not).
  sleep 12
  if "$HARNESS" check >/tmp/chaos-check.log 2>&1; then
    pass "duplicate delivery: $redelivered redelivered event(s) changed nothing"
  else
    fail "duplicate delivery: $(head -3 /tmp/chaos-check.log | tr '\n' ' ')"
  fi
}

# Re-announces an order's earliest event after the ones that followed it.
scenario_reorder() {
  printf '\n%s\n' "--- reordered delivery ---"
  reset_state
  if ! "$HARNESS" storm --orders "$ORDERS" --units "$UNITS" --quantity 1 --sku "$SKU" \
       --concurrency "$CONCURRENCY" --settle-timeout "$SETTLE_TIMEOUT" >/tmp/chaos-burst.log 2>&1; then
    fail "reordered delivery: the baseline burst did not pass"
    return
  fi
  local late=0 order
  for order in $(settled_orders); do
    "$HARNESS" inject reorder --order "$order" >/dev/null 2>&1 && late=$((late + 1))
  done
  note "re-announced the first event of $late confirmed order(s) after the last"
  sleep 12
  if "$HARNESS" check >/tmp/chaos-check.log 2>&1; then
    pass "reordered delivery: $late out-of-order event(s) changed nothing"
  else
    fail "reordered delivery: $(head -3 /tmp/chaos-check.log | tr '\n' ' ')"
  fi
}

# A share of payments declines mid-burst, so the compensation path that starts at payment runs:
# each declined order must fail, release its stock and hold no money. Stock is plentiful here so
# the storm can assert the exact count — every order confirms except the declined ones.
scenario_declines() {
  local percent=${DECLINE_PERCENT:-20} orders=${DECLINE_ORDERS:-100} declined
  printf '\n%s\n' "--- payment declines (${percent}%) ---"
  # --no-deps: without it compose also recreates any dependency whose configuration it thinks has
  # changed — order-service included, if this shell's SPRING_PROFILES_ACTIVE differs from the one the
  # stack was started with — and the scenario would be measuring a restart it did not intend.
  PAYMENT_DECLINE_PERCENT=$percent docker compose up -d --wait --no-deps payment-service >/dev/null 2>&1
  "$HARNESS" setup --sku "$SKU" --units "$orders" >/dev/null || { fail "could not reset state"; return; }
  if "$HARNESS" storm --orders "$orders" --units "$orders" --quantity 1 --sku "$SKU" \
      --concurrency "$CONCURRENCY" --settle-timeout "$SETTLE_TIMEOUT" >/tmp/chaos-burst.log 2>&1; then
    declined=$(grep -oE '[0-9]+ of them for a declined payment' /tmp/chaos-burst.log | grep -oE '^[0-9]+')
    if [ "${declined:-0}" -gt 0 ]; then
      pass "payment declines: $declined declined order(s) failed and were compensated; the rest confirmed"
    else
      fail "payment declines: nothing was declined, so the path never ran"
    fi
  else
    fail "payment declines: $(tail -3 /tmp/chaos-burst.log | tr '\n' ' ')"
  fi
  PAYMENT_DECLINE_PERCENT=0 docker compose up -d --wait --no-deps payment-service >/dev/null 2>&1
}

# Messages on the dead-letter topic: the sum of its partitions' high watermarks.
dead_lettered() {
  docker compose exec -T redpanda rpk topic describe fulfillops.dlt -p 2>/dev/null \
    | awk 'NR > 1 { sum += $NF } END { print sum + 0 }'
}

# A message no consumer can read. Each of the four services' consumers must set it aside on the
# dead-letter topic rather than drop it or stall behind it, and orders placed afterwards must
# still go through exactly.
scenario_poison() {
  local before after
  printf '\n%s\n' "--- poison message ---"
  before=$(dead_lettered)
  "$HARNESS" inject poison --order ORD-POISON >/dev/null || { fail "poison message: could not publish"; return; }
  sleep 15
  after=$(dead_lettered)
  note "dead-letter topic: $before -> $after message(s)"
  reset_state
  if ! "$HARNESS" storm --orders "$ORDERS" --units "$UNITS" --quantity 1 --sku "$SKU" \
      --concurrency "$CONCURRENCY" --settle-timeout "$SETTLE_TIMEOUT" >/tmp/chaos-burst.log 2>&1; then
    fail "poison message: orders after it did not go through: $(tail -2 /tmp/chaos-burst.log | tr '\n' ' ')"
  elif [ $((after - before)) -ne 4 ]; then
    fail "poison message: expected one dead letter per consuming service (4), got $((after - before))"
  else
    pass "poison message: all 4 consumers dead-lettered it, and the next burst went through exactly"
  fi
}

# --- run ----------------------------------------------------------------------

if [ $# -eq 0 ]; then
  SCENARIOS="inventory payment fulfilment order broker duplicate reorder declines poison"
else
  SCENARIOS="$*"
fi

printf 'chaos: %s orders for %s unit(s) at %s concurrent clients, damage %ss in\n' \
  "$ORDERS" "$UNITS" "$CONCURRENCY" "$KILL_AFTER"

for scenario in $SCENARIOS; do
  case "$scenario" in
    inventory)  scenario_kill_service inventory-service ;;
    payment)    scenario_kill_service payment-service ;;
    fulfilment) scenario_kill_service fulfilment-service ;;
    order)      scenario_kill_service order-service ;;
    broker)     scenario_restart_broker ;;
    duplicate)  scenario_duplicate ;;
    reorder)    scenario_reorder ;;
    declines)   scenario_declines ;;
    poison)     scenario_poison ;;
    *)          printf 'unknown scenario: %s\n' "$scenario" >&2; exit 2 ;;
  esac
done

printf '\n'
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32mall scenarios held\033[0m\n'
else
  printf '\033[31mat least one scenario broke an invariant\033[0m\n'
fi
exit "$FAILED"
