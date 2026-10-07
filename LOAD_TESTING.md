# Load testing

`GridLoadTest` (TestNG group `load`) runs full session lifecycles in parallel against a grid:
`POST /session` → N × `GET /session/{id}/timeouts` → optional hold → `DELETE /session/{id}`.
It prints and writes a summary to `target/load-report/summary.json`: error rate, error breakdown,
p50/p95/p99/max latency of session creation, commands and deletion, sessions per second.
The run fails when the error rate or the p95 of session creation exceeds the configured thresholds.

Load tests are excluded from the regular build and run only with the `load` profile.

## Against a real grid with real devices

```bash
mvn -Pload test -Dgrid.url=http://grid.example.com:4444/wd/hub \
  -Dload.caps='{"platformName":"Android","appium:automationName":"UiAutomator2"}' \
  -Dload.sessions=100 -Dload.concurrency=10 -Dload.maxErrorRate=0.05
```

Sessions start real Appium sessions on devices, so keep `load.concurrency` at or below the number of
matching devices unless you want to test queueing (`GRID_NEW_SESSION_WAIT_TIMEOUT`).
Capabilities can be read from a file: `-Dload.caps=@caps.json`.

## Against the hub only (fake nodes)

The test can register in-process fake Appium nodes (`MobileRemoteProxy`, answering `/status`,
`/status-adb`, `/status-wda` and session endpoints), so the hub logic and its throughput can be
stressed without devices. The hub must be able to reach this machine at `load.fakeNodes.host`.

Local hub in Docker (builds the image from this repo):

```bash
scripts/load-local.sh -Dload.fakeNodes=40 -Dload.sessions=600 -Dload.concurrency=80
```

Remote hub (STF must be disabled on it, fake devices are not in STF):

```bash
mvn -Pload test -Dgrid.url=http://grid:4444/wd/hub -Dload.fakeNodes=20 -Dload.fakeNodes.host=<ip reachable from the hub>
```

## Options

| Property | Default | Meaning |
|---|---|---|
| `grid.url` | — (test is skipped) | Grid endpoint, e.g. `http://host:4444/wd/hub` |
| `load.sessions` | 20 | Total sessions |
| `load.concurrency` | 5 | Sessions in parallel |
| `load.caps` | `{"platformName":"Android"}` | Requested capabilities, JSON or `@file.json` |
| `load.commands` | 3 | Light commands per session |
| `load.sessionHoldMs` | 0 | Keep each session open before deleting it |
| `load.newSessionTimeoutSec` | 600 | Client timeout for `POST /session` (includes queue time) |
| `load.maxErrorRate` | 0 | Allowed failed sessions ratio, 0..1 |
| `load.maxP95CreateMs` | 0 (off) | Upper bound for p95 of session creation |
| `load.fakeNodes` | 0 | Fake nodes to register (0 = real devices) |
| `load.fakeNodes.host` | `localhost` | Host the hub uses to reach fake nodes (`host.docker.internal` for Docker Desktop) |
| `load.fakeNodes.platform` | `ANDROID` | `ANDROID` or `IOS` |
| `load.fakeNodes.sessionDelayMs` | 0 | Simulated Appium session startup time |
| `load.reportDir` | `target/load-report` | Report directory |
