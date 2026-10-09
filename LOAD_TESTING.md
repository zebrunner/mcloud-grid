# Load testing

`GridLoadTest` (TestNG group `load`) runs full session lifecycles in parallel against a grid:
`POST /session` → N × `GET /session/{id}/timeouts` (or commands for a fixed duration) → optional hold → `DELETE /session/{id}`.
It prints and writes a summary to `target/load-report/summary.json`: error rate, error breakdown,
p50/p95/p99/max latency of session creation, commands and deletion, sessions per second.
The run fails when the error rate or the p95 of session creation exceeds the configured thresholds.

Load tests are excluded from the regular build and run only with the `load` profile.

## Against a real grid with real devices

```bash
tests/mvn.sh -Pload test -Dgrid.url=http://grid.example.com:4444/wd/hub \
  -Dload.caps='{"platformName":"Android","appium:automationName":"UiAutomator2"}' \
  -Dload.sessions=100 -Dload.concurrency=10 -Dload.maxErrorRate=0.05
```

Sessions start real Appium sessions on devices, so keep `load.concurrency` at or below the number of
matching devices unless you want to test queueing (`GRID_NEW_SESSION_WAIT_TIMEOUT`).
Capabilities can also be read from a file: `-Dload.caps=@caps.json`, but **create `caps.json` first**
in the directory where you run Maven (or specify an absolute path). A missing file causes the load
test constructor to fail during TestNG discovery, before any session is created.

To send **preemptive HTTP Basic Auth on every WebDriver request** (including the final DELETE), set
the credentials in environment variables. Use HTTPS for a remote grid; Basic Auth does not encrypt credentials.
Do not put credentials in `grid.url` or `-D` arguments: URLs and process arguments can appear in logs and process listings.

```bash
export LOAD_GRID_USERNAME='your-user'
printf 'Grid password: '; IFS= read -rs LOAD_GRID_PASSWORD; echo; export LOAD_GRID_PASSWORD
tests/mvn.sh -Pload test -Dgrid.url=https://grid.example.com/wd/hub \
  -Dload.caps='{"appium:deviceName":"ANY"}' \
  -Dload.sessions=2 -Dload.concurrency=1 \
  -Dload.commandDurationMs=5000 -Dload.commandIntervalMs=1000
unset LOAD_GRID_USERNAME LOAD_GRID_PASSWORD
```

The example runs 2 session lifecycles, one at a time; *each* session sends `GET /timeouts`
roughly once per second for 5 seconds and then closes. Increase session count, concurrency and duration
only after checking how many matching devices are free. Duration begins **after** session creation,
so time spent waiting in the grid queue does not consume command time. `load.commands` is ignored
when `load.commandDurationMs` is positive. `load.sessionHoldMs` adds idle time *after* the commands.
The summary in `target/load-report/summary.json` contains command counts, latency percentiles,
errors and throughput; it does not include the credentials.
If a run is forcibly killed, some sessions may remain on the grid until its normal timeout.

## Against the hub only (fake nodes)

The test can register in-process fake Appium nodes (`MobileRemoteProxy`, answering `/status`,
`/status-adb`, `/status-wda` and session endpoints), so the hub logic and its throughput can be
stressed without devices. The hub must be able to reach this machine at `load.fakeNodes.host`.

Local hub in Docker (builds the image from this repo; `make load` runs a short version):

```bash
tests/load_local.sh -Dload.fakeNodes=40 -Dload.sessions=600 -Dload.concurrency=80
```

Remote hub (STF must be disabled on it, fake devices are not in STF):

```bash
tests/mvn.sh -Pload test -Dgrid.url=http://grid:4444/wd/hub -Dload.fakeNodes=20 -Dload.fakeNodes.host=<ip reachable from the hub>
```

## Options

| Property                                   | Default                      | Meaning                                                                           |
|--------------------------------------------|------------------------------|-----------------------------------------------------------------------------------|
| `grid.url`                                 | — (test is skipped)          | Grid endpoint, e.g. `http://host:4444/wd/hub`                                     |
| `load.sessions`                            | 20                           | Total sessions                                                                    |
| `load.concurrency`                         | 5                            | Sessions in parallel                                                              |
| `load.caps`                                | `{"platformName":"Android"}` | Requested capabilities, JSON or `@file.json`                                      |
| `load.commands`                            | 3                            | Light commands per session                                                        |
| `load.commandDurationMs`                   | 0 (off)                      | Send commands for this long per session instead of using `load.commands`          |
| `load.commandIntervalMs`                   | 1000                         | Delay between commands in duration mode (must be positive)                        |
| `load.sessionHoldMs`                       | 0                            | Keep each session open before deleting it                                         |
| `load.newSessionTimeoutSec`                | 600                          | Client timeout for `POST /session` (includes queue time)                          |
| `load.maxErrorRate`                        | 0                            | Allowed failed sessions ratio, 0..1                                               |
| `load.maxP95CreateMs`                      | 0 (off)                      | Upper bound for p95 of session creation                                           |
| `load.fakeNodes`                           | 0                            | Fake nodes to register (0 = real devices)                                         |
| `load.fakeNodes.host`                      | `localhost`                  | Host the hub uses to reach fake nodes (`host.docker.internal` for Docker Desktop) |
| `load.fakeNodes.platform`                  | `ANDROID`                    | `ANDROID` or `IOS`                                                                |
| `load.fakeNodes.sessionDelayMs`            | 0                            | Simulated Appium session startup time                                             |
| `load.reportDir`                           | `target/load-report`         | Report directory                                                                  |
| `LOAD_GRID_USERNAME`, `LOAD_GRID_PASSWORD` | unset                        | Optional Basic Auth credentials, passed via environment (both required)           |
