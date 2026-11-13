#!/bin/bash
# Checks of the docker image: build, unprivileged user, the start without STF, the validation of the configuration (exit 0),
# config generation from env vars, options
# passed to the JVM and the hub, hub status, node registration with node tokens verified by a fake STF and graceful
# shutdown.
# IMAGE overrides the tag of the built image.
source "$(dirname "$0")/lib.sh"
require_cmd docker
require_cmd python3
docker info > /dev/null 2>&1 || skip "requires a running docker daemon"
cd "$REPO" || exit 1

IMAGE="${IMAGE:-mcloud-grid:test}"
CONTAINER="mcloud-grid-test-$$"
NO_STF_CONTAINER="${CONTAINER}-no-stf"
STF_DOWN_CONTAINER="${CONTAINER}-stf-down"
INVALID_CONTAINER="${CONTAINER}-invalid"
STF_TOKEN="stf-admin-token-$$"
# the node token of the node enrolled at 192.0.2.3 in the fake STF; its secret part is looked for in the logs
NODE_SECRET="AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"
NODE_TOKEN="mcn1.docker-test.${NODE_SECRET}"
UNKNOWN_NODE_TOKEN="mcn1.docker-test.ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210_-edcba"
STF_PID=""

# called by the EXIT trap of lib.sh
# shellcheck disable=SC2329
cleanup() {
  docker rm -f "$CONTAINER" "$NO_STF_CONTAINER" "$STF_DOWN_CONTAINER" "$INVALID_CONTAINER" > /dev/null 2>&1 || true
  if [[ -n "$STF_PID" ]]; then
    kill "$STF_PID" 2> /dev/null || true
  fi
}

# start_fake_stf: the STF API the hub uses, on a free port of this machine (written to $WORK/stf.port): the node
# registry knows NODE_TOKEN for 192.0.2.3, every device is present; the requests are logged to $WORK/stf.log
start_fake_stf() {
  STF_TOKEN="$STF_TOKEN" NODE_TOKEN="$NODE_TOKEN" python3 - "$WORK/stf.port" "$WORK/stf.log" << 'PY' &
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Stf(BaseHTTPRequestHandler):
    def answer(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_request(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        with open(sys.argv[2], "a", encoding="utf-8") as log:
            log.write(f"{self.command} {self.path} {self.headers.get('Authorization') == 'Bearer ' + os.environ['STF_TOKEN']}\n")
        if self.headers.get("Authorization") != "Bearer " + os.environ["STF_TOKEN"]:
            return self.answer(401, {"success": False})
        if self.command == "POST" and self.path == "/api/v1/nodes/verify":
            request = json.loads(body or b"{}")
            if request.get("token") == os.environ["NODE_TOKEN"] and request.get("host") == "192.0.2.3":
                return self.answer(200, {"label": "docker-test", "address": "192.0.2.3"})
            return self.answer(403, {"success": False})
        if self.command == "GET" and self.path == "/api/v1/user":
            return self.answer(200, {"success": True, "user": {"name": "grid", "email": "grid@example.com"}})
        if self.command == "GET" and self.path == "/api/v1/devices":
            return self.answer(200, {"success": True, "devices": []})
        if self.command == "GET" and self.path.startswith("/api/v1/devices/"):
            serial = self.path.rsplit("/", 1)[1]
            return self.answer(200, {"success": True, "device": {"serial": serial, "present": True, "ready": True, "status": 3}})
        return self.answer(404, {"success": False})

    do_GET = do_POST = do_PUT = do_DELETE = handle_request

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("0.0.0.0", 0), Stf)
with open(sys.argv[1], "w", encoding="utf-8") as port:
    port.write(str(server.server_address[1]))
server.serve_forever()
PY
  STF_PID=$!
  for _ in $(seq 1 50); do
    [[ -s "$WORK/stf.port" ]] && return 0
    sleep 0.1
  done
  return 1
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

# wait_hub <container>: waits for the status of the hub, prints its HTTP status
wait_hub() {
  local status=""
  for _ in $(seq 1 60); do
    status="$(docker exec "$1" curl -s -o /dev/null -w '%{http_code}' http://localhost:4444/wd/hub/status 2> /dev/null)"
    [[ "$status" == "200" ]] && break
    sleep 1
  done
  echo "$status"
}

section "configuration"
# every invalid variable of config.json is logged, then the container exits with 0: a restart does not fix it
docker run --name "$INVALID_CONTAINER" --env GRID_TIMEOUT=1h --env GRID_DEBUG= --env GRID_CLEAN_UP_CYCLE=0 \
  --env GRID_NEW_SESSION_WAIT_TIMEOUT=0600000 --env 'GRID_CAPABILITY_MATCHER=a"b' "$IMAGE" > "$WORK/invalid.log" 2>&1
check "an invalid configuration exits with 0" "0" "$?"
logs="$(cat "$WORK/invalid.log")"
check_contains "an invalid number is logged" "GRID_TIMEOUT '1h' is not a whole number of at least 0." "$logs"
check_contains "an empty boolean is logged" "GRID_DEBUG '' is not true or false." "$logs"
check_contains "a number below the minimum is logged" "GRID_CLEAN_UP_CYCLE '0' is not a whole number of at least 1." "$logs"
check_contains "a number that is not a JSON number is logged" "GRID_NEW_SESSION_WAIT_TIMEOUT '0600000' is not a whole number" "$logs"
check_contains "an invalid class name is logged" "GRID_CAPABILITY_MATCHER 'a\\\"b' is not the name of a Java class." "$logs"
check_not_contains "the hub is not started with an invalid configuration" "Starting Selenium hub" "$logs"
check "the log lines of the validation are JSON" "ok" \
  "$(python3 -c 'import json, sys; [json.loads(line) for line in open(sys.argv[1], encoding="utf-8")]; print("ok")' "$WORK/invalid.log" 2>&1)"
docker rm -f "$INVALID_CONTAINER" > /dev/null 2>&1

section "STF"
# without STF_URL and STF_TOKEN the hub starts (the first start of MCloud, before an access token of STF) and refuses every
# node: no node token could be verified (see "node registration")
docker run --detach --name "$NO_STF_CONTAINER" --env STF_TOKEN="$STF_TOKEN" --env JAVA_HEAP_OPTS="-Xms64m -Xmx512m" "$IMAGE" > /dev/null
check "the hub is up without STF_URL" "200" "$(wait_hub "$NO_STF_CONTAINER")"
logs="$(docker logs "$NO_STF_CONTAINER" 2>&1)"
check_contains "the missing STF is logged" \
  '"level":"SEVERE","logger":"com.zebrunner.mcloud.grid.MobileGridRegistry"' "$(grep 'STF_URL and STF_TOKEN are not set' <<< "$logs")"

if start_fake_stf; then
  pass "the fake STF starts"
else
  fail "the fake STF starts" "no port" "the python3 server of the fake STF did not start"
  finish
fi
# host-gateway: the hub reaches the fake STF on this machine (Docker Desktop resolves host.docker.internal itself)
stf_url="http://host.docker.internal:$(cat "$WORK/stf.port")"

# an STF that does not answer (TEST-NET-1): a node token is not verified
docker run --detach --name "$STF_DOWN_CONTAINER" \
  --env STF_URL=http://192.0.2.50:7100 \
  --env STF_TOKEN="$STF_TOKEN" \
  --env CHECK_NODE_REACHABILITY=false \
  --env JAVA_HEAP_OPTS="-Xms64m -Xmx512m" \
  "$IMAGE" > /dev/null
check "the hub starts with an STF that does not answer" "200" "$(wait_hub "$STF_DOWN_CONTAINER")"

section "container"
docker run --detach --name "$CONTAINER" \
  --add-host=host.docker.internal:host-gateway \
  --env STF_URL="$stf_url" \
  --env STF_TOKEN="$STF_TOKEN" \
  --env CHECK_NODE_REACHABILITY=false \
  --env GRID_TIMEOUT=77 \
  --env GRID_CLEAN_UP_CYCLE=3000 \
  --env GRID_NEW_SESSION_WAIT_TIMEOUT=12345 \
  --env JAVA_HEAP_OPTS="-Xms64m -Xmx512m" \
  --env JAVA_OPTS="-Dmcloud.test=true" \
  --env SE_OPTS="-debug" \
  "$IMAGE" > /dev/null

check "hub status responds" "200" "$(wait_hub "$CONTAINER")"

config="$(docker exec "$CONTAINER" cat /opt/selenium/config.json)"
check_contains "config.json takes GRID_TIMEOUT" '"timeout": 77,' "$config"
check_contains "config.json takes GRID_NEW_SESSION_WAIT_TIMEOUT" '"newSessionWaitTimeout": 12345,' "$config"
check_contains "config.json takes the default GRID_BROWSER_TIMEOUT" '"browserTimeout": 900,' "$config"
check_contains "config.json takes GRID_CLEAN_UP_CYCLE" '"cleanUpCycle": 3000,' "$config"
check_not_contains "config.json names no proxy: every node names its own" '"proxy"' "$config"
check_contains "config.json uses the mobile capability matcher" '"capabilityMatcher": "com.zebrunner.mcloud.grid.MobileCapabilityMatcher"' "$config"
check_contains "config.json uses the mobile registry" '"registry": "com.zebrunner.mcloud.grid.MobileGridRegistry"' "$config"
check_contains "config.json turns the lifecycle servlet off" '"org.openqa.grid.web.servlet.LifecycleServlet"' "$config"

for servlet in DevicesServlet AllSessionsServlet; do
  check "${servlet} is registered" '{"value":[]}' \
    "$(docker exec "$CONTAINER" curl -s "http://localhost:4444/grid/admin/${servlet}")"
done
check_contains "MetricsServlet is registered" "mcloud_grid_new_session_requests 0" \
  "$(docker exec "$CONTAINER" curl -s http://localhost:4444/grid/admin/MetricsServlet)"
check "TerminateSessionServlet is registered (no running session)" "404" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Authorization: Bearer key' \
    'http://localhost:4444/grid/admin/TerminateSessionServlet?udid=x')"
# the WebSocket support of Jetty starts with the servlet in the context of the hub of Selenium; the Playwright sessions
# are off by default (PLAYWRIGHT_ENABLED)
check "PlaywrightServlet is registered, the Playwright sessions are off" "501" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    'http://localhost:4444/grid/admin/PlaywrightServlet/no-such-session')"
check_contains "a Playwright session is refused while the option is off" "set PLAYWRIGHT_ENABLED=true of the hub" \
  "$(docker exec "$CONTAINER" curl -s -H 'Content-Type: application/json' \
    -d '{"capabilities":{"alwaysMatch":{"platformName":"Android","appium:automationName":"Playwright"}}}' \
    http://localhost:4444/wd/hub/session)"
check "grid console responds" "200" \
  "$(docker exec "$CONTAINER" curl -s -o /dev/null -w '%{http_code}' http://localhost:4444/grid/console)"

section "node registration"
# register <host> <proxy class> <token> [<container>]: the registration request of a node as the node config of zebrunner/appium,
# prints the HTTP status
register() {
  local custom='{}'
  if [[ -n "$3" ]]; then
    custom="{\"registrationToken\": \"$3\"}"
  fi
  docker exec -i "${4:-$CONTAINER}" curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary @- http://localhost:4444/grid/register << _EOF
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
check "a node without a node token is rejected" "500" "$(register 192.0.2.1 "$mobile" "")"
check "a node with a token that is not a node token is rejected" "500" "$(register 192.0.2.2 "$mobile" wrong-token)"
check "a node with its node token at its address is accepted" "200" "$(register 192.0.2.3 "$mobile" "$NODE_TOKEN")"
check "a node with an unknown node token is rejected" "500" "$(register 192.0.2.5 "$mobile" "$UNKNOWN_NODE_TOKEN")"
check "a node token at another address is rejected" "500" "$(register 192.0.2.6 "$mobile" "$NODE_TOKEN")"
check "a node token STF does not verify is rejected" "500" "$(register 192.0.2.7 "$mobile" "$NODE_TOKEN" "$STF_DOWN_CONTAINER")"
check "without STF_URL a node with its node token is rejected" "500" "$(register 192.0.2.3 "$mobile" "$NODE_TOKEN" "$NO_STF_CONTAINER")"
check_contains "the hub without STF_URL logs why" "STF_URL and STF_TOKEN of the hub are not set" "$(docker logs "$NO_STF_CONTAINER" 2>&1)"
check_contains "the hub verifies the node tokens with STF_TOKEN" "POST /api/v1/nodes/verify True" "$(cat "$WORK/stf.log")"
check_not_contains "the hub sends no request to STF without STF_TOKEN" " False" "$(cat "$WORK/stf.log")"
logs="$(docker logs "$STF_DOWN_CONTAINER" 2>&1)"
check_contains "the unverified node token is logged" \
  "Node http://192.0.2.7:4723 has a node token, but STF could not verify it (STF did not answer)" "$logs"
check_not_contains "the node token is not in the log of the hub without STF" "$NODE_SECRET" "$logs"
answer="$(wait_registered 192.0.2.3)"
check_contains "the node with its node token is registered" '"success": true' "$answer"
check_not_contains "the hub does not show the node token of a node" "$NODE_SECRET" "$answer"
check_not_contains "the console does not show the node token of a node" "$NODE_SECRET" \
  "$(docker exec "$CONTAINER" curl -s http://localhost:4444/grid/console)"
check "the hub answers a node of another proxy class (Selenium adds it in the background)" "200" \
  "$(register 192.0.2.4 org.openqa.grid.selenium.proxy.DefaultRemoteProxy "$NODE_TOKEN")"
sleep 2
for host in 192.0.2.1 192.0.2.2 192.0.2.4 192.0.2.5 192.0.2.6; do
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
check_contains "a node without a node token is logged" "Node http://192.0.2.1:4723 has no node token, so it is not registered" "$logs"
check_contains "a token that is not a node token is logged" "Node http://192.0.2.2:4723 has a token that is not a node token" "$logs"
check_contains "an unknown node token is logged" "Node http://192.0.2.5:4723 has an unknown or revoked node token, so it is not registered" "$logs"
check_contains "a node token at another address is logged" "Node http://192.0.2.6:4723 has an unknown or revoked node token" "$logs"
check_contains "the registration requests are in the debug log" "getting the following registration request" "$logs"
check_not_contains "the node token is not in the log (debug included)" "$NODE_SECRET" "$logs"
check_not_contains "the STF token is not in the log" "$STF_TOKEN" "$logs"

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
