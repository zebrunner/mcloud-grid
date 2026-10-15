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
}
