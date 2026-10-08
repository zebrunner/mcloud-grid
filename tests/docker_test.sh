#!/bin/bash
# Checks of the docker image: build, unprivileged user, config generation from env vars,
# options passed to the JVM and the hub, hub status and graceful shutdown.
# IMAGE overrides the tag of the built image.
source "$(dirname "$0")/lib.sh"
require_cmd docker
require_cmd python3
docker info > /dev/null 2>&1 || skip "requires a running docker daemon"
cd "$REPO" || exit 1

IMAGE="${IMAGE:-mcloud-grid:test}"
CONTAINER="mcloud-grid-test-$$"

# called by the EXIT trap of lib.sh
# shellcheck disable=SC2329
cleanup() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
}

section "image"
if ! build_output="$(docker build --quiet --tag "$IMAGE" . 2>&1)"; then
  fail "image builds" "docker build failed" "$build_output"
  finish
fi
pass "image builds"
check "hub runs as an unprivileged user" "1000" "$(docker run --rm --entrypoint id "$IMAGE" -u)"
check "config.json is not baked into the image" "absent" \
  "$(docker run --rm --entrypoint sh "$IMAGE" -c 'test -e /opt/selenium/config.json && echo present || echo absent')"

section "container"
docker run --detach --name "$CONTAINER" \
  --env GRID_TIMEOUT=77 \
  --env GRID_NEW_SESSION_WAIT_TIMEOUT=12345 \
  --env JAVA_HEAP_OPTS="-Xms64m -Xmx512m" \
  --env JAVA_OPTS="-Dmcloud.test=true" \
  --env SE_OPTS="-debug" \
  "$IMAGE" > /dev/null

status=""
for _ in $(seq 1 60); do
  status="$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' http://localhost:4444/wd/hub/status 2> /dev/null)"
  [[ "$status" == "200" ]] && break
  sleep 1
done
check "hub status responds" "200" "$status"

config="$(docker exec "$CONTAINER" cat /opt/selenium/config.json)"
check_contains "config.json takes GRID_TIMEOUT" '"timeout": 77,' "$config"
check_contains "config.json takes GRID_NEW_SESSION_WAIT_TIMEOUT" '"newSessionWaitTimeout": 12345,' "$config"
check_contains "config.json uses the mobile proxy" '"proxy": "com.zebrunner.mcloud.grid.MobileRemoteProxy"' "$config"
check_contains "config.json uses the mobile capability matcher" '"capabilityMatcher": "com.zebrunner.mcloud.grid.MobileCapabilityMatcher"' "$config"

for servlet in DevicesServlet AllSessionsServlet; do
  check "${servlet} is registered" '{"value":[]}' \
    "$(docker exec "$CONTAINER" curl -s "http://localhost:4444/grid/admin/${servlet}")"
done
check_contains "MetricsServlet is registered" "mcloud_grid_new_session_requests 0" \
  "$(docker exec "$CONTAINER" curl -s http://localhost:4444/grid/admin/MetricsServlet)"
check "TerminateSessionServlet is registered (needs STF)" "501" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Authorization: Bearer key' \
    'http://localhost:4444/grid/admin/TerminateSessionServlet?udid=x')"
check "grid console responds" "200" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' http://localhost:4444/grid/console)"

cmdline="$(docker exec "$CONTAINER" sh -c 'tr "\0" " " < /proc/$(pgrep java)/cmdline')"
check_contains "JAVA_HEAP_OPTS reach the JVM" "-Xms64m -Xmx512m" "$cmdline"
check_contains "JAVA_OPTS reach the JVM" "-Dmcloud.test=true" "$cmdline"
check_contains "SE_OPTS reach the hub" "-hubConfig /opt/selenium/config.json -debug" "$cmdline"

section "shutdown"
start=$SECONDS
docker stop --time 20 "$CONTAINER" > /dev/null
elapsed=$((SECONDS - start))
if [[ "$elapsed" -lt 10 ]]; then
  pass "docker stop shuts the hub down gracefully"
else
  fail "docker stop shuts the hub down gracefully" "took ${elapsed}s" "SIGTERM was not handled, docker stop waited ${elapsed}s"
fi
check_contains "shutdown is logged" "shutdown complete" "$(docker logs "$CONTAINER" 2>&1 | tail -5)"

docker logs "$CONTAINER" > "$WORK/docker.log" 2>&1
check "all Docker log lines are JSON (including Selenium debug and entrypoint)" "ok" \
  "$(
    python3 - "$WORK/docker.log" << 'PY'
import json
import sys

components = set()
with open(sys.argv[1], encoding="utf-8") as log:
    for number, line in enumerate(log, 1):
        try:
            record = json.loads(line)
            assert all(record.get(field) for field in ("timestamp", "level", "component", "category", "message"))
            components.add(record["component"])
        except (ValueError, AssertionError) as exc:
            print(f"line {number}: {line[:160].strip()} ({exc})")
            sys.exit(1)
print("ok" if {"selenium", "mcloud-grid"} <= components else f"missing components: {components}")
PY
  )"

finish
