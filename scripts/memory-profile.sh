#!/usr/bin/env bash
# Where each JVM service's memory goes, and whether anything was killed for running out of it.
#
#   JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary SPRING_PROFILES_ACTIVE= docker compose up -d --wait
#   since=$(date +%s)
#   ...run the storm, chaos.sh or load-test.sh...
#   ./scripts/memory-profile.sh "$since"
#
# Per service it prints the container's limit, current and peak memory from its cgroup, how much of
# that is anonymous (process) memory rather than page cache, the out-of-memory kills the kernel
# recorded, and Docker's restart count. With native memory tracking on, it also breaks the JVM's own
# share down: heap, class metadata, code cache, threads, and what is left. "untracked" is anonymous
# memory the JVM does not account for, mostly the C allocator's arenas.
#
# OOM kills are counted from Docker's event log since the given time, not from the cgroup, because
# a restarted container gets a fresh cgroup and would forget it was ever killed.

set -uo pipefail
cd "$(dirname "$0")/.."

SINCE=${1:-0}
SERVICES=${SERVICES:-"order-service inventory-service payment-service fulfilment-service cs-console"}
JDK_IMAGE=${JDK_IMAGE:-eclipse-temurin:21-jdk}

mb() { echo $(( ${1:-0} / 1048576 )); }

# The runtime image is a JRE with no jcmd, so a JDK container joins the target's PID namespace and
# attaches through the target's /tmp, as the target's own user and group.
nmt() {
  docker run --rm --pid="container:$1" --user 0 --cap-add SYS_PTRACE --cap-add KILL "$JDK_IMAGE" sh -c '
    T=/proc/1/root/tmp
    if [ ! -S $T/.java_pid1 ]; then
      touch $T/.attach_pid1 && chown "$(stat -c %u /proc/1)" $T/.attach_pid1 && kill -QUIT 1
      for i in $(seq 20); do [ -S $T/.java_pid1 ] && break; sleep 0.25; done
    fi
    rm -rf /tmp && ln -s $T /tmp
    exec setpriv --reuid="$(stat -c %u $T/.java_pid1)" --regid="$(stat -c %g $T/.java_pid1)" \
      --clear-groups jcmd 1 VM.native_memory summary' 2>/dev/null
}

# committed bytes of one NMT category, in MB
category() { grep -E "^-[[:space:]]+$1 \(" <<<"$2" | sed -E 's/.*committed=([0-9]+)KB.*/\1/' | awk '{s+=$1} END {print int(s/1024)}'; }

printf '%-20s %6s %8s %6s %6s %6s %4s %8s | %5s %5s %5s %5s %5s %5s %9s\n' \
  service limit current peak anon atmax oom restarts heap class code thread other total untracked
for service in $SERVICES; do
  id=$(docker compose ps -q "$service" 2>/dev/null)
  [ -n "$id" ] || { printf '%-20s not running\n' "$service"; continue; }
  # atmax: how many times the container reached its limit and the kernel had to reclaim to go on.
  read -r limit current peak anon atmax < <(docker exec "$id" sh -c '
    cd /sys/fs/cgroup
    echo "$(cat memory.max) $(cat memory.current) $(cat memory.peak 2>/dev/null || echo 0)" \
      "$(awk "/^anon /{print \$2}" memory.stat) $(awk "/^max /{print \$2}" memory.events)"')
  restarts=$(docker inspect -f '{{.RestartCount}}' "$id")
  ooms=$(docker events --since "$SINCE" --until "$(date +%s)" --filter "container=$id" --filter event=oom \
    --format x 2>/dev/null | wc -l | tr -d ' ')

  summary=$(nmt "$id")
  if grep -q '^Total' <<<"$summary"; then
    total=$(sed -nE 's/^Total: .*committed=([0-9]+)KB.*/\1/p' <<<"$summary"); total=$((total / 1024))
    heap=$(category 'Java Heap' "$summary")
    class=$(( $(category 'Class' "$summary") + $(category 'Metaspace' "$summary") + $(category 'Symbol' "$summary") ))
    code=$(category 'Code' "$summary")
    thread=$(category 'Thread' "$summary")
    other=$((total - heap - class - code - thread))
    untracked=$(( $(mb "$anon") - total ))
  else
    heap=- class=- code=- thread=- other=- total=- untracked=-
  fi
  printf '%-20s %6s %8s %6s %6s %6s %4s %8s | %5s %5s %5s %5s %5s %5s %9s\n' \
    "$service" "$(mb "$limit")" "$(mb "$current")" "$(mb "$peak")" "$(mb "$anon")" "$atmax" "$ooms" "$restarts" \
    "$heap" "$class" "$code" "$thread" "$other" "$total" "$untracked"
done
echo '(MB; class = class metadata + metaspace + symbols; peak is since the container last started)'
