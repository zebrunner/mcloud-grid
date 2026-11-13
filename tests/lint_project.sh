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
  section "java"
  run_check "checkstyle" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true verify
  run_check "spotbugs" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true verify

  section "tests/mvn.sh"
  # a docker stub writes its arguments and the password it gets from its environment
  mkdir -p "$WORK/bin"
  cat > "$WORK/bin/docker" << 'SH'
#!/bin/bash
printf '%s\n' "$@" > "$DOCKER_ARGS"
echo "environment: ${LOAD_GRID_PASSWORD:-}" >> "$DOCKER_ARGS"
SH
  chmod +x "$WORK/bin/docker"
  DOCKER_ARGS="$WORK/docker.args" PATH="$WORK/bin:$PATH" MAVEN_CACHE_DIR="$WORK/m2" \
    LOAD_GRID_USERNAME=load-user LOAD_GRID_PASSWORD="load-secret-$$" tests/mvn.sh -v
  local args
  args="$(cat "$WORK/docker.args")"
  check_contains "the credentials of the load tests reach the container by name" $'--env\nLOAD_GRID_PASSWORD\n' "$args"
  check_contains "docker gets the password from its environment" "environment: load-secret-$$" "$args"
  check_not_contains "the password is not an argument of docker (ps, logs)" "LOAD_GRID_PASSWORD=" "$args"
}
