#!/bin/bash
# shellcheck disable=SC2034
# The settings and the checks of the project for tests/lint.sh (CONVENTIONS.md): mcloud-grid.

scripts=(entrypoint.sh generate_config tests/*.sh)
generated=(target)
tab_files=(Makefile pom.xml)
# Maven: tests/mvn.sh runs it in docker, the lint job with a JDK sets MVN=mvn
MVN=${MVN:-tests/mvn.sh}

# project_checks: checkstyle and SpotBugs of the Java sources
project_checks() {
  section "Dockerfile ENV"
  # every variable the hub and its scripts read has its default in ENV of the Dockerfile (CONVENTIONS.md#dockerfile)
  local env_vars read_vars
  env_vars="$(sed -n -E 's/^(ENV)?[[:space:]]+([A-Z][A-Z0-9_]*)=.*/\2/p' Dockerfile | sort -u)"
  read_vars="$({
    grep -rhoE '(getenv|EnvUtils\.get[A-Za-z]+)\("[A-Z][A-Z0-9_]*"' src/main/java | sed -E 's/.*\("//; s/"$//'
    grep -ohE '\$\{?(GRID_[A-Z0-9_]+|[A-Z0-9_]+_OPTS)' entrypoint.sh generate_config | sed -E 's/^[^A-Z]+//'
  } | sort -u)"
  check "the Dockerfile has a default of every variable of the configuration" "" \
    "$(comm -13 <(echo "$env_vars") <(echo "$read_vars") | sed 's/$/ is missing in ENV of the Dockerfile/')"

  section "java"
  run_check "checkstyle" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true verify
  run_check "spotbugs" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true verify
}
