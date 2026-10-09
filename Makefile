# Project checks; `make check` runs the linters, the unit/integration tests, the docker image checks and a short load test
SHELL := /bin/bash
VENV := .venv
export PATH := $(CURDIR)/$(VENV)/bin:$(PATH)

.DEFAULT_GOAL := help
.PHONY: help check lint test docker load

help: ## this list
	@grep -E '^[a-z]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/'

check: lint test docker load ## everything below, as CI runs it

lint: $(VENV)/.installed ## the linters (tests/lint.sh): yaml, workflows, Dockerfile, markdown, shell, checkstyle, spotbugs
	tests/lint.sh

test: ## unit and STF integration tests with the coverage report in target/site/jacoco
	mvn -B verify

docker: ## the docker image checks (tests/docker_test.sh)
	tests/docker_test.sh

load: ## a short load test of the image with fake Appium nodes (tests/load_local.sh), see LOAD_TESTING.md
	tests/load_local.sh -Dload.fakeNodes=10 -Dload.sessions=200 -Dload.concurrency=20 -Dload.maxErrorRate=0

# The tools of tests/requirements-lint.txt in .venv; the hadolint-py wheel for macOS is broken, brew provides it there
$(VENV)/.installed: tests/requirements-lint.txt
	python3 -m venv $(VENV)
	@if [[ "$$(uname)" == Darwin ]]; then \
	  grep -v '^hadolint-py' tests/requirements-lint.txt > $(VENV)/requirements.txt; \
	else \
	  cp tests/requirements-lint.txt $(VENV)/requirements.txt; \
	fi
	$(VENV)/bin/pip install --quiet --disable-pip-version-check --requirement $(VENV)/requirements.txt
	@command -v hadolint > /dev/null || { echo "hadolint is missing: brew install hadolint"; exit 1; }
	touch $@
