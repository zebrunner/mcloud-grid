# Project checks; `make check` runs the linters, the unit/integration tests, the docker image checks and a short load test (CONVENTIONS.md)
SHELL := /bin/bash
VENV := .venv
MVN ?= tests/mvn.sh
export PATH := $(CURDIR)/$(VENV)/bin:$(PATH)
export MVN

# The image (CONVENTIONS.md#makefile): `make build VERSION=<tag>`, `make push VERSION=<tag>` (docker login first);
# IMAGE_REPO overrides the repository, PLATFORMS the platforms of the push
IMAGE_REPO ?= public.ecr.aws/zebrunner/mcloud-grid
VERSION ?= dev
PLATFORMS ?= linux/amd64,linux/arm64

.DEFAULT_GOAL := help
.PHONY: help check lint test docker load build push

help: ## this list
	@grep -E '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/'

check: ## everything below, as CI runs it; every step runs, the failed ones fail the target
	@status=0; for target in lint test docker load; do $(MAKE) --no-print-directory $$target || status=1; done; exit $$status

lint: $(VENV)/.installed ## the linters and the text file checks (tests/lint.sh), checkstyle, spotbugs
	tests/lint.sh

test: ## unit and STF integration tests with the coverage report in target/site/jacoco
	$(MVN) -B verify

docker: ## the docker image checks (tests/docker_test.sh)
	tests/docker_test.sh

load: ## a short load test of the image with fake Appium nodes (tests/load_local.sh), see LOAD_TESTING.md
	tests/load_local.sh -Dload.fakeNodes=10 -Dload.sessions=200 -Dload.concurrency=20 -Dload.maxErrorRate=0

build: ## the image IMAGE_REPO:VERSION for the platform of this machine: make build VERSION=<tag>
	docker build --progress=plain --provenance=false --build-arg VERSION=$(VERSION) --tag $(IMAGE_REPO):$(VERSION) .

push: ## the image IMAGE_REPO:VERSION for PLATFORMS, built by docker buildx and pushed: make push VERSION=<tag>
	@[[ "$(VERSION)" != dev ]] || { echo "make push VERSION=<tag>: the tag of the image is required"; exit 1; }
	@docker buildx inspect mcloud > /dev/null 2>&1 || docker buildx create --name mcloud --driver docker-container --driver-opt network=host > /dev/null
	docker buildx build --builder mcloud --progress=plain --provenance=false --platform $(PLATFORMS) \
	  --build-arg VERSION=$(VERSION) --tag $(IMAGE_REPO):$(VERSION) --push .

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
