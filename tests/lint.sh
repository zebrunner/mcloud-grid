#!/bin/bash
# Static checks of the project files, every linter run is a check (also in JUnit XML with JUNIT_DIR);
# the tools: pip install -r tests/requirements-lint.txt (hadolint on macOS: brew install hadolint), Docker or a JDK 11+ for Maven
# The shared part is the same in all the repositories, see CONVENTIONS.md.
source "$(dirname "$0")/lib.sh"
cd "$REPO" || exit 1

# Maven: tests/mvn.sh runs it in docker, CI sets MVN=mvn
MVN=${MVN:-tests/mvn.sh}

# the shell scripts of the project
scripts=(entrypoint.sh generate_config tests/*.sh)
# the generated files kept byte for byte and the build output: no text file checks
generated=(target)
# the files indented with tabs
tab_files=(Makefile pom.xml)

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
# the files are listed: actionlint looks for the project by .git otherwise, and the sources may come without it
run_check "actionlint" actionlint .github/workflows/*.yml

section "Dockerfile"
run_check "hadolint" hadolint Dockerfile

section "markdown"
run_check "pymarkdown" pymarkdown scan ./*.md

section "README"
# every env var of the Dockerfile is documented
env_vars="$(sed -n -E 's/^(ENV)?[[:space:]]+([A-Z][A-Z0-9_]*)=.*/\2/p; s/^ENV ([A-Z][A-Z0-9_]*)=.*/\1/p' Dockerfile | sort -u)"
check "README documents every env var of the Dockerfile" "" \
  "$(for var in $env_vars; do grep -qF "\`${var}\`" README.md || echo "${var} is missing in README.md"; done)"
# the license of CONVENTIONS.md: LICENSE, the README section and the label of the image
check_contains "LICENSE is the Apache License 2.0" "Apache License" "$(head -3 LICENSE 2> /dev/null)"
check_contains "README links LICENSE" "[Apache License 2.0](LICENSE)" "$(cat README.md)"
check_contains "the image is labeled Apache-2.0" 'org.opencontainers.image.licenses="Apache-2.0"' "$(cat Dockerfile)"

section "java"
run_check "checkstyle" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true verify
run_check "spotbugs" "$MVN" -B -q -Plint -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true verify

section "shell"
for script in "${scripts[@]}"; do
  run_check "bash -n ${script}" bash -n "$script"
done
run_check "shellcheck" shellcheck --severity=info "${scripts[@]}"
# style of .editorconfig
run_check "shfmt" shfmt -d "${scripts[@]}"
# the rules of CONVENTIONS.md that shfmt and shellcheck do not check, the comment lines are skipped
check "tests use [[ ]] only" "" \
  "$(grep -nE '(^|[[:space:];&|(!])\[[[:space:]]' "${scripts[@]}" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#')"
check "functions are defined without the function keyword" "" \
  "$(grep -nE '^[[:space:]]*function[[:space:]]' "${scripts[@]}")"

section "text files"
# every text file of the project but the generated ones
prune=(-name .git -o -name .venv -o -name .idea)
for path in "${generated[@]}"; do
  prune+=(-o -path "./${path}")
done
text_files=()
while IFS= read -r file; do
  grep -Iq '' "$file" 2> /dev/null && text_files+=("$file")
done < <(find . \( "${prune[@]}" \) -prune -o -type f -print | sort)
check "ASCII characters only" "" "$(LC_ALL=C grep -n '[^[:print:][:space:]]' "${text_files[@]}")"
check "LF line ends" "" "$(grep -l $'\r' "${text_files[@]}")"
check "no trailing whitespace" "" "$(grep -nE '[[:blank:]]+$' "${text_files[@]}")"
check "a final newline" "" "$(for file in "${text_files[@]}"; do [[ -z "$(tail -c 1 "$file")" ]] || echo "$file"; done)"
tabbed="$(grep -l $'\t' "${text_files[@]}")"
for file in "${tab_files[@]}"; do
  tabbed="$(grep -vxF "./${file}" <<< "$tabbed")"
done
check "tabs only in ${tab_files[*]}" "" "$tabbed"

[[ "$FAILED" -eq 0 ]] && echo "lint: ok"
finish
