#!/bin/bash
# Builds the grid image, starts it in Docker and stresses it with in-process fake Appium nodes.
# Usage: tests/load_local.sh [extra -D options for GridLoadTest]
#   e.g. tests/load_local.sh -Dload.fakeNodes=40 -Dload.sessions=600 -Dload.concurrency=80
# The hub uses an in-process fake STF of the test container (FAKE_STF_PORT, 18090 by default; latency:
# -Dload.fakeStf.latencyMs=...), which enrolls the fake nodes and verifies their node tokens.
# HUB_ENV="NAME=value ..." passes more env vars to the hub container.
set -euo pipefail

cd "$(dirname "$0")/.."

MVN=${MVN:-tests/mvn.sh}
IMAGE=${IMAGE:-mcloud-grid:local}
CONTAINER=${CONTAINER:-mcloud-grid-load}
NETWORK=${NETWORK:-${CONTAINER}-net}
TEST_CONTAINER=${TEST_CONTAINER:-${CONTAINER}-tests}
PORT=${PORT:-4444}
FAKE_STF_PORT=${FAKE_STF_PORT:-18090}

# the hub reaches the fake STF in the test container through the docker network
hub_env=(--env "STF_URL=http://${TEST_CONTAINER}:${FAKE_STF_PORT}" --env "STF_TOKEN=fake-stf-token")
for variable in ${HUB_ENV:-}; do
  hub_env+=(--env "$variable")
done

docker build -q -t "$IMAGE" . > /dev/null
docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
docker rm -f "$TEST_CONTAINER" > /dev/null 2>&1 || true
docker network create "$NETWORK" > /dev/null 2>&1 || true
docker run -d --name "$CONTAINER" --network "$NETWORK" -p "$PORT:4444" "${hub_env[@]}" "$IMAGE" > /dev/null
trap 'docker logs "$CONTAINER" > target/load-report/hub.log 2>&1 || true; docker rm -f "$TEST_CONTAINER" >/dev/null 2>&1 || true; docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; docker network rm "$NETWORK" >/dev/null 2>&1 || true' EXIT

echo "waiting for hub on :$PORT"
for _ in $(seq 1 60); do
  curl -sf "http://localhost:$PORT/wd/hub/status" > /dev/null && break
  sleep 1
done

mkdir -p target/load-report
container_mvn_args="--network $NETWORK --name $TEST_CONTAINER --network-alias $TEST_CONTAINER"
if [[ -n "${MVN_DOCKER_ARGS:-}" ]]; then
  container_mvn_args+=" ${MVN_DOCKER_ARGS}"
fi
MVN_DOCKER_ARGS="$container_mvn_args" "$MVN" -B -q -Pload test \
  -Dgrid.url="http://$CONTAINER:4444/wd/hub" \
  -Dload.fakeNodes=10 \
  -Dload.fakeNodes.host="$TEST_CONTAINER" \
  -Dload.sessions=200 \
  -Dload.concurrency=20 \
  -Dload.fakeStf.port="$FAKE_STF_PORT" \
  "$@"
