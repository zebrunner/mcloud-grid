#!/bin/bash
# Static checks of the project files, every linter run is a check (also in JUnit XML with JUNIT_DIR);
# the tools: pip install -r tests/requirements-lint.txt, Docker and a JDK 11+ in the Maven container
source "$(dirname "$0")/lib.sh"
cd "$REPO" || exit 1

MVN=${MVN:-tests/mvn.sh}

missing=""
for tool in actionlint hadolint pymarkdown shellcheck shfmt yamllint; do
  command -v "$tool" > /dev/null 2>&1 || missing="${missing} ${tool}"
done
if [[ -n "$missing" ]]; then
  fail "lint tools are installed" "missing:${missing}" \
    "missing tools:${missing} (pip install -r tests/requirements-lint.txt, hadolint on macOS: brew install hadolint)"
  finish
fi

section "yaml"
run_check "yamllint" yamllint --strict .

section "GitHub workflows"
run_check "actionlint" actionlint

section "Dockerfile"
run_check "hadolint" hadolint Dockerfile

section "markdown"
run_check "pymarkdown" pymarkdown scan README.md LOAD_TESTING.md

section "shell"
scripts=(entrypoint.sh generate_config tests/*.sh)
for script in "${scripts[@]}"; do
  run_check "bash -n ${script}" bash -n "$script"
done
run_check "shellcheck" shellcheck --severity=info "${scripts[@]}"
# style of .editorconfig
run_check "shfmt" shfmt -d "${scripts[@]}"

section "java"
run_check "checkstyle" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true verify
run_check "spotbugs" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true verify

[[ "$FAILED" -eq 0 ]] && echo "lint: ok"
finish
