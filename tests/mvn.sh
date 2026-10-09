#!/bin/bash
# Runs Maven in Docker so the host does not need a local Maven installation.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
MVN_IMAGE=${MVN_IMAGE:-maven:3.9.16-eclipse-temurin-11}
MAVEN_CACHE_DIR=${MAVEN_CACHE_DIR:-${HOME}/.m2}

mkdir -p "$MAVEN_CACHE_DIR"

docker_args=(
  --rm
  --user "$(id -u):$(id -g)"
  --volume "$REPO:$REPO"
  --workdir "$REPO"
  --volume "$MAVEN_CACHE_DIR:/tmp/.m2"
  --env MAVEN_CONFIG=/tmp/.m2
  --env HOME=/tmp
)

if [[ -n "${MVN_DOCKER_ARGS:-}" ]]; then
  # shellcheck disable=SC2206
  extra_docker_args=(${MVN_DOCKER_ARGS})
  docker_args+=("${extra_docker_args[@]}")
fi

if [[ -n "${MVN_PASSTHROUGH_ENV_VARS:-}" ]]; then
  for var_name in ${MVN_PASSTHROUGH_ENV_VARS}; do
    if [[ -n "${!var_name+x}" ]]; then
      docker_args+=(--env "${var_name}=${!var_name}")
    fi
  done
fi

exec docker run "${docker_args[@]}" "$MVN_IMAGE" mvn -Duser.home=/tmp "$@"
