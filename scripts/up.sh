#!/usr/bin/env bash
# Starts everything a visitor needs to watch the system work: the four services and the CS console,
# Postgres, Redpanda, Jaeger, Prometheus and Grafana, then the seller app and the 3D ops floor in
# live mode. The dev container's one command; works anywhere with Docker and Node 22.
#
#   ./scripts/up.sh        # start everything (again)
#   ./scripts/up.sh down   # stop everything
#
# The stack starts without the demo fixtures, which are inserted straight into the orders table and
# would be reported by the invariant checks as orders that never went through the saga. Place orders
# from the seller app, or run the storm, and watch them cross the ops floor.
set -euo pipefail
cd "$(dirname "$0")/.."

# Front-end dev servers run in the background; their pids and logs live here.
RUN=/tmp/fulfillops

stop_frontends() {
  for pidfile in "$RUN"/*.pid; do
    [ -e "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
    rm -f "$pidfile"
  done
}

start_frontend() {
  local name=$1 dir=$2
  shift 2
  [ -d "$dir/node_modules" ] || (cd "$dir" && npm ci --silent)
  (cd "$dir" && nohup "$@" >"$RUN/$name.log" 2>&1 & echo $! >"$RUN/$name.pid")
}

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 120); do
    curl -sf -o /dev/null "$url" && return 0
    sleep 1
  done
  echo "$name did not come up; see $RUN/$name.log" >&2
  exit 1
}

mkdir -p "$RUN"
if [ "${1:-}" = down ]; then
  stop_frontends
  docker compose --profile observability down
  exit 0
fi

[ -f .env ] || cp .env.example .env
SPRING_PROFILES_ACTIVE= docker compose --profile observability up -d --build --wait

stop_frontends
start_frontend seller-dashboard seller-dashboard ./node_modules/.bin/ng serve
# VITE_CS_URL is empty so the browser asks the ops floor's own origin, which proxies to cs-console.
start_frontend ops-floor ops-floor env VITE_SOURCE=live VITE_CS_URL= ./node_modules/.bin/vite
wait_for seller-dashboard http://localhost:4200
wait_for ops-floor http://localhost:5174

cat <<'EOF'

FulfillOps is up.

  Seller app     http://localhost:4200    sign in as seller / seller-dev-password
  Ops floor      http://localhost:5174    live: orders appear as they are placed
  Grafana        http://localhost:3000    the order saga dashboard
  Jaeger         http://localhost:16686   one trace per order, across every service

  1,000 orders for 100 units, then every invariant:
    ./scripts/ordering-harness.py setup --units 100 && ./scripts/ordering-harness.py storm --orders 1000 --units 100

  Break things mid-order and check nothing was oversold, lost or double-charged (~15 minutes):
    ./scripts/chaos.sh

  Stop:  ./scripts/up.sh down
EOF
