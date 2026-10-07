#!/bin/bash
# Runs all linters: shellcheck (shell scripts), hadolint (Dockerfile), checkstyle + spotbugs (Java).
# Usage: scripts/lint.sh [--no-java]
set -euo pipefail

cd "$(dirname "$0")/.."

status=0

echo "==> shellcheck"
shellcheck entrypoint.sh generate_config scripts/*.sh || status=1

echo "==> hadolint"
hadolint Dockerfile || status=1

if [[ "${1:-}" != "--no-java" ]]; then
  echo "==> checkstyle + spotbugs"
  mvn -B -q -Plint -DskipTests verify || status=1
fi

exit $status
