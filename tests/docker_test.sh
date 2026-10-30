#!/bin/bash
# Checks of the docker image: build, unprivileged user, no start without NODE_REGISTRATION_TOKEN, config generation from
# env vars, options passed to the JVM and the hub, hub status, node registration and graceful shutdown.
# IMAGE overrides the tag of the built image.
source "$(dirname "$0")/lib.sh"
require_cmd docker
require_cmd python3
docker info > /dev/null 2>&1 || skip "requires a running docker daemon"
cd "$REPO" || exit 1

IMAGE="${IMAGE:-mcloud-grid:test}"
CONTAINER="mcloud-grid-test-$$"
NO_TOKEN_CONTAINER="${CONTAINER}-no-token"
TOKEN="docker-test-token-$$"

# called by the EXIT trap of lib.sh
# shellcheck disable=SC2329
cleanup() {
  docker rm -f "$CONTAINER" "$NO_TOKEN_CONTAINER" > /dev/null 2>&1 || true
}

section "image"
if ! docker build --progress=plain --tag "$IMAGE" . > "$WORK/docker-build.log" 2>&1; then
  fail "image builds" "docker build failed" "$(tail -100 "$WORK/docker-build.log")"
  finish
fi
pass "image builds"
check "hub runs as an unprivileged user" "1000" "$(docker run --rm --entrypoint id "$IMAGE" -u)"
check "config.json is not baked into the image" "absent" \
  "$(docker run --rm --entrypoint sh "$IMAGE" -c 'test -e /opt/selenium/config.json && echo present || echo absent')"

section "registration token"
# the registry fails the start of the hub without NODE_REGISTRATION_TOKEN
docker run --detach --name "$NO_TOKEN_CONTAINER" --env JAVA_HEAP_OPTS="-Xms64m -Xmx512m" "$IMAGE" > /dev/null
for _ in $(seq 1 60); do
  [[ "$(docker inspect --format '{{.State.Running}}' "$NO_TOKEN_CONTAINER")" == "false" ]] && break
  sleep 1
done
check "the hub exits without NODE_REGISTRATION_TOKEN" "false 1" \
  "$(docker inspect --format '{{.State.Running}} {{.State.ExitCode}}' "$NO_TOKEN_CONTAINER")"
logs="$(docker logs "$NO_TOKEN_CONTAINER" 2>&1)"
check_contains "the missing NODE_REGISTRATION_TOKEN is logged" \
  '"level":"SEVERE","logger":"com.zebrunner.mcloud.grid.MobileGridRegistry"' "$(grep 'NODE_REGISTRATION_TOKEN is not set' <<< "$logs")"
check_not_contains "the hub is not up without NODE_REGISTRATION_TOKEN" "Selenium Grid hub is up and running" "$logs"

section "container"
docker run --detach --name "$CONTAINER" \
  --env NODE_REGISTRATION_TOKEN="$TOKEN" \
  --env CHECK_NODE_REACHABILITY=false \
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
check_contains "config.json takes the default GRID_BROWSER_TIMEOUT" '"browserTimeout": 900,' "$config"
check_contains "config.json uses the mobile proxy" '"proxy": "com.zebrunner.mcloud.grid.MobileRemoteProxy"' "$config"
check_contains "config.json uses the mobile capability matcher" '"capabilityMatcher": "com.zebrunner.mcloud.grid.MobileCapabilityMatcher"' "$config"
check_contains "config.json uses the mobile registry" '"registry": "com.zebrunner.mcloud.grid.MobileGridRegistry"' "$config"
check_contains "config.json turns the lifecycle servlet off" '"org.openqa.grid.web.servlet.LifecycleServlet"' "$config"

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

section "node registration"
# register <host> <proxy class> <token>: the registration request of a node as the node config of zebrunner/appium,
# prints the HTTP status
register() {
  local custom='{}'
  if [[ -n "$3" ]]; then
    custom="{\"registrationToken\": \"$3\"}"
  fi
  docker exec -i "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary @- http://localhost:4444/grid/register << _EOF
{"capabilities": [{"maxInstances": 1, "platformName": "ANDROID", "udid": "udid-$1", "deviceName": "Pixel"}],
 "configuration": {"proxy": "$2", "url": "http://$1:4723/wd/hub", "host": "$1", "port": 4723, "id": "http://$1:4723",
   "maxSession": 1, "register": true, "custom": $custom}}
_EOF
}
# registered <host>: the answer of the hub about the node, which a node asks for every few seconds
registered() {
  docker exec "$CONTAINER" curl -s "http://localhost:4444/grid/api/proxy?id=http://$1:4723"
}
# wait_registered <host>: the hub adds a node in the background
wait_registered() {
  local answer=""
  for _ in $(seq 1 20); do
    answer="$(registered "$1")"
    [[ "$answer" == *'"success": true'* ]] && break
    sleep 0.5
  done
  echo "$answer"
}
mobile=com.zebrunner.mcloud.grid.MobileRemoteProxy
check "a node without the registration token is rejected" "500" "$(register 192.0.2.1 "$mobile" "")"
check "a node with another registration token is rejected" "500" "$(register 192.0.2.2 "$mobile" wrong-token)"
check "a node with the registration token is accepted" "200" "$(register 192.0.2.3 "$mobile" "$TOKEN")"
answer="$(wait_registered 192.0.2.3)"
check_contains "the node with the registration token is registered" '"success": true' "$answer"
check_not_contains "the hub does not show the registration token of a node" "$TOKEN" "$answer"
check_not_contains "the console does not show the registration token of a node" "$TOKEN" \
  "$(docker exec "$CONTAINER" curl -s http://localhost:4444/grid/console)"
check "the hub answers a node of another proxy class (Selenium adds it in the background)" "200" \
  "$(register 192.0.2.4 org.openqa.grid.selenium.proxy.DefaultRemoteProxy "$TOKEN")"
sleep 2
for host in 192.0.2.1 192.0.2.2 192.0.2.4; do
  check_contains "the rejected node ${host} is not registered" '"success": false' "$(registered "$host")"
done
docker exec "$CONTAINER" curl -s -o /dev/null 'http://localhost:4444/lifecycle-manager?action=shutdown'
sleep 2
check "/lifecycle-manager does not shut the hub down" "200" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' http://localhost:4444/wd/hub/status)"

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
logs="$(docker logs "$CONTAINER" 2>&1)"
check_contains "a node of another proxy class is logged" "registers with the proxy org.openqa.grid.selenium.proxy.DefaultRemoteProxy" "$logs"
check_contains "a node without the registration token is logged" "Node http://192.0.2.1:4723 has no or another NODE_REGISTRATION_TOKEN" "$logs"
check_contains "the registration requests are in the debug log" "getting the following registration request" "$logs"
check_not_contains "the registration token is not in the log (debug included)" "$TOKEN" "$logs"

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
