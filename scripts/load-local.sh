#!/bin/bash
# Builds the grid image, starts it in Docker and stresses it with in-process fake Appium nodes.
# Usage: scripts/load-local.sh [extra -D options for GridLoadTest]
#   e.g. scripts/load-local.sh -Dload.fakeNodes=40 -Dload.sessions=600 -Dload.concurrency=80
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-mcloud-grid:local}
CONTAINER=${CONTAINER:-mcloud-grid-load}
PORT=${PORT:-4444}

docker build -q -t "$IMAGE" . >/dev/null
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
# host-gateway makes host.docker.internal resolvable on Linux too (Docker Desktop provides it itself)
docker run -d --name "$CONTAINER" -p "$PORT:4444" --add-host=host.docker.internal:host-gateway "$IMAGE" >/dev/null
trap 'docker logs "$CONTAINER" > target/load-report/hub.log 2>&1 || true; docker rm -f "$CONTAINER" >/dev/null' EXIT

echo "waiting for hub on :$PORT"
for _ in $(seq 1 60); do
  curl -sf "http://localhost:$PORT/wd/hub/status" >/dev/null && break
  sleep 1
done

mkdir -p target/load-report
mvn -B -q -Pload test \
  -Dgrid.url="http://localhost:$PORT/wd/hub" \
  -Dload.fakeNodes=10 \
  -Dload.fakeNodes.host=host.docker.internal \
  -Dload.sessions=200 \
  -Dload.concurrency=20 \
  "$@"
