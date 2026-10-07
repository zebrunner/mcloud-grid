# Zebrunner Device Farm - Selenium Hub

Enhanced Selenium/Appium Grid for automating Android and iOS devices including TVs (Android, Tizen, Apple) and emulators/simulators.
It is a Selenium 3 hub with a mobile proxy (`MobileRemoteProxy`) and capability matcher (`MobileCapabilityMatcher`):
one Appium node is one device, devices are reserved in [STF](https://github.com/zebrunner/stf) for the time of a session.

Feel free to support the development with a [**donation**](https://www.paypal.com/donate/?hosted_button_id=MNHYYCYHAKUVA) for the next improvements.

<p align="center">
  <a href="https://zebrunner.com/"><img alt="Zebrunner" src="https://github.com/zebrunner/zebrunner/raw/master/docs/img/zebrunner_intro.png"></a>
</p>

## Usage

> Follow the installation and configuration guide in [MCloud](https://github.com/zebrunner/mcloud) to reuse this image effectively.

### Build

```bash
docker build . -t zebrunner/mcloud-grid:latest
```

The image build compiles and packages the hub; tests and linters run separately (see [Development](#development)).

### Run

```bash
docker run -d -p 4444:4444 -e GRID_NEW_SESSION_WAIT_TIMEOUT=240000 \
  -e GRID_TIMEOUT=60 -e GRID_BROWSER_TIMEOUT=60 \
  -e STF_URL=http://stf.example.com -e STF_TOKEN=<token> \
  --name mcloud-grid zebrunner/mcloud-grid:latest
```

The hub runs as an unprivileged user and stops gracefully on `docker stop`.

## Configuration

### Grid

| Env var | Default | Meaning |
|---|---|---|
| `GRID_NEW_SESSION_WAIT_TIMEOUT` | `600000` | How long a new session request waits in the queue, ms |
| `GRID_TIMEOUT` | `150` | Client inactivity timeout of a session, s |
| `GRID_BROWSER_TIMEOUT` | `0` | Timeout of a command on the node, s (0 = none) |
| `GRID_CLEAN_UP_CYCLE` | `5000` | How often the hub checks timed out sessions, ms |
| `GRID_THROW_ON_CAPABILITY_NOT_PRESENT` | `true` | Reject requests no registered device can serve; see [Queueing](#queueing) |
| `GRID_JETTY_MAX_THREADS` | `-1` | Jetty threads of the hub (-1 = default) |
| `GRID_DEBUG` | `false` | Debug logging of the hub |
| `GRID_PROXY`, `GRID_CAPABILITY_MATCHER` | mobile proxy and matcher | Classes of the node proxy and capability matcher |
| `JAVA_HEAP_OPTS` | `-Xms1G -Xmx4G` | JVM heap of the hub |
| `JAVA_OPTS` | | Other JVM options |
| `SE_OPTS` | | Extra hub options, e.g. `-debug`; `-servlets` replaces the [servlets](#endpoints) of the hub config |
| `CHECK_NODE_REACHABILITY` | `true` | Reject the registration of a node the hub cannot connect to |
| `NODE_REACHABILITY_TIMEOUT` | `2` | Connection timeout of that check, s |
| `MAX_NEW_COMMAND_TIMEOUT` | | Upper limit of `appium:newCommandTimeout`, s; bigger and disabled (`0`) values are limited (no limit when not set) |
| `MCLOUD_LOG_LEVEL` | `INFO` | Log level of the grid code, `FINE` for details; Selenium logs stay as they are |

### STF and device health

STF integration is enabled when both `STF_URL` and `STF_TOKEN` are set.

| Env var | Default | Meaning |
|---|---|---|
| `STF_URL` | | STF address |
| `STF_TOKEN` | | Access token of the STF user that reserves devices for automation; when the user is an STF admin, a device that does not answer the reservation is also marked unhealthy in STF |
| `STF_TIMEOUT` | `3600` | Reservation timeout of a device in STF, s |
| `CHECK_APPIUM_STATUS` | `false` | Check `/status-adb` (Android) or `/status-wda` (iOS) of the node before a session |
| `UNHEALTHY_MOBILE_TIMEOUT` | `60` | A device failing the Appium status check is skipped for, s |
| `INACTIVITY_RELEASE_TIMEOUT` | `60` | A device whose session timed out before it started is skipped for, s |
| `STF_DEVICE_INVALID_RESPONSE_IGNORE_TIMEOUT` | `600` | A device with an invalid STF status or failed reservation is skipped for, s |
| `STF_DEVICE_UNAUTHORIZED_IGNORE_TIMEOUT` | `600` | A device unauthorized in STF is skipped for, s |
| `STF_DEVICE_UNHEALTHY_IGNORE_TIMEOUT` | `60` | An unhealthy or not ready device is skipped for, s |
| `STF_DEVICE_MANUALLY_RESERVED_TIMEOUT` | `180` | A device reserved in STF by another user is skipped for, s |

### Capabilities

| Capability | Meaning |
|---|---|
| `platformName` | `Android`, `iOS`, ... |
| `appium:platformVersion` | Exact (`13`), range (`11-13`), minimum (`12+`) or list (`12,14`); `7` matches `7.0` |
| `appium:deviceName`, `appium:udid` | One value or a comma separated list |
| `zebrunner:deviceType` | `phone`, `tablet`, `tv`, `tvOS`, ...; `tvOS` devices get `platformName=tvOS` |
| `zebrunner:STF_TOKEN` | Personal STF token: the device is reserved as that user and is not returned after the session |
| `zebrunner:STF_TIMEOUT` | Reservation timeout in STF for this session, s |
| `zebrunner:enableAdb` | Android: wait for the remote ADB connection of STF before the session |

The node capabilities of the reserved device are passed to the session as `zebrunner:slotCapabilities`.

### Queueing

With `GRID_THROW_ON_CAPABILITY_NOT_PRESENT=true` a request for a device that is not registered fails at once
(`cannot find : Capabilities {...}`, or `Empty pool of VM for setup` when no device is registered at all). With `false` it waits in the queue for up to `GRID_NEW_SESSION_WAIT_TIMEOUT`
and gets a device that registers meanwhile (a restarted device, a new emulator), otherwise it fails with
`Request timed out waiting for a node to become available`.

## Endpoints

| Endpoint | Content |
|---|---|
| `/grid/console` | Selenium grid console, with the UDID of every device |
| `/grid/admin/DevicesServlet` | Devices as JSON: platform, type, node, status `free`/`busy`/`ignored`/`down`, why and until when a device is ignored, its session (Appium session id, start, inactivity, last command) |
| `/grid/admin/AllSessionsServlet` | Active sessions as JSON with the requested capabilities and the device |
| `/grid/admin/MetricsServlet` | Prometheus metrics: `mcloud_grid_devices{platform,status}`, `mcloud_grid_sessions`, `mcloud_grid_new_session_requests` (the queue) |
| `/wd/hub/status` | Hub status, also the healthcheck of the image |

The servlets are registered in the hub config by `generate_config`.
`com.zebrunner.mcloud.grid.servlets.ProxyInfo` (registration requests of the nodes) is not registered by default.

## Logs

Every line about a device starts with `[<udid>][<hub session id>]`, one line per event of a session:

```text
INFO [MobileRemoteProxy.getNewSession] - [emulator-5554][0f85...] Device 'Pixel 7' (ANDROID 14) is selected for the session, starting the Appium session.
INFO [MobileRemoteProxy.afterCommand] - [emulator-5554][0f85...] Appium session '06c7...' is started on 'Pixel 7'.
INFO [MobileRemoteProxy.afterSession] - [emulator-5554][0f85...] Session '06c7...' is finished after 95s. Last command: DELETE - /session/06c7... executed.
```

Warnings are problems with their cause and consequence, e.g.
`Appium /status-adb check failed (HTTP 500): ...` and `Device 'Pixel 7' is not ready for a session, it is ignored for 60 seconds.`
The configuration in effect is logged once as a `[CONFIGURATION]` line. `MCLOUD_LOG_LEVEL=FINE` adds the details
(skipped devices, STF device data, remoteConnect steps); `SE_OPTS=-debug` turns on the debug logs of Selenium itself.

## Development

Requirements: JDK 11 or newer, maven, docker, python 3 (for the linters), hadolint on macOS (`brew install hadolint`).

```bash
make check   # everything CI runs
make lint    # yaml, GitHub workflows, Dockerfile, markdown, shell scripts, checkstyle, spotbugs
make test    # unit and STF integration tests, coverage in target/site/jacoco
make docker  # docker image checks: user, config generation, options, endpoints, graceful shutdown
make load    # short load test of the image with fake Appium nodes
```

`tests/lint.sh` and `tests/docker_test.sh` write JUnit XML into `$JUNIT_DIR` when it is set, maven writes it into `target/surefire-reports`.
Tests with STF enabled (TestNG group `stf`) run in their own JVM against a WireMock STF stub.
Load tests against real grids are described in [LOAD_TESTING.md](LOAD_TESTING.md).
CI (`.github/workflows/ci.yml`) runs the same checks on pushes and pull requests: jobs `lint`, `tests` and `docker`
(the image checks and a load test with fake Appium nodes); dependabot proposes updates weekly.

## Documentation and free support

* [Zebrunner PRO](https://zebrunner.com)
* [Zebrunner CE](https://zebrunner.github.io/community-edition)
* [Zebrunner Reporting](https://zebrunner.com/documentation)
* [Carina Guide](http://zebrunner.github.io/carina)
* [Demo Project](https://github.com/zebrunner/carina-demo)
* [Telegram Channel](https://t.me/zebrunner)
