#!/usr/bin/env bash
# How big a machine the whole stack needs. Builds and runs everything inside one Docker-in-Docker
# container capped at CPUS and MEMORY, which is what a Codespace or any single-machine host looks
# like from the inside, and reports the container's peak memory and the time for each phase.
#
#   CPUS=2 MEMORY=8g ./scripts/measure-host.sh     # a 2-core, 8 GB Codespace
#
# The build is cold — empty Maven and image caches — because a fresh Codespace has neither. The
# front-end dev servers are not included; they run beside Docker, not inside it. What is measured is
# the committed tree (git archive HEAD), not uncommitted edits.
set -euo pipefail
cd "$(dirname "$0")/.."

CPUS=${CPUS:-2}
MEMORY=${MEMORY:-8g}
BOX=fulfillops-measure

in_box() { docker exec "$BOX" sh -c "cd /src && $1"; }
peak_mb() { in_box "sort -n /tmp/mem | tail -1" | awk '{printf "%d", $1 / 1048576}'; }
phase() {
  local label=$1 command=$2 started
  in_box ': > /tmp/mem'
  started=$(date +%s)
  in_box "$command" >"/tmp/$BOX-$label.log" 2>&1 || { echo "$label failed: see /tmp/$BOX-$label.log" >&2; exit 1; }
  printf '%-22s %6ss   peak %6s MB\n' "$label" "$(( $(date +%s) - started ))" "$(peak_mb)"
}

docker rm -f "$BOX" >/dev/null 2>&1 || true
trap 'docker rm -f "$BOX" >/dev/null 2>&1 || true' EXIT
docker run -d --privileged --name "$BOX" --cpus "$CPUS" --memory "$MEMORY" docker:28-dind >/dev/null
until docker exec "$BOX" docker info >/dev/null 2>&1; do sleep 1; done
docker exec "$BOX" apk add --no-cache bash curl python3 >/dev/null
git archive HEAD | docker exec -i "$BOX" sh -c 'mkdir -p /src && tar -x -C /src && cp /src/.env.example /src/.env'
# The box's own cgroup covers dockerd and every container inside it, sampled once a second.
docker exec -d "$BOX" sh -c 'while true; do cat /sys/fs/cgroup/memory.current >> /tmp/mem; sleep 1; done'

printf 'box: %s CPUs, %s memory limit\n\n' "$CPUS" "$MEMORY"
phase "cold build" "docker compose --profile observability build"
phase "cold start" "SPRING_PROFILES_ACTIVE= docker compose --profile observability up -d --wait"
phase "1,000-order storm" "./scripts/ordering-harness.py setup --units 100 && ./scripts/ordering-harness.py storm --orders 1000 --units 100"
phase "idle, 60 s later" "sleep 60"
in_box "./scripts/ordering-harness.py check" | tail -1
