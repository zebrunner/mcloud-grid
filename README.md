# Zebrunner Device Farm - Selenium Hub

Enhanced Selenium/Appium Grid for automating Android and iOS devices including TVs (Android, Tizen, Apple) and emulators/simulators. It is a Selenium 3 hub with a mobile proxy (`MobileRemoteProxy`) and capability matcher (`MobileCapabilityMatcher`): one Appium node is one device, devices are reserved in [STF](https://github.com/zebrunner/stf) for the time of a session.

Feel free to support the development with a [**donation**](https://www.paypal.com/donate/?hosted_button_id=MNHYYCYHAKUVA) for the next improvements.

<p style="text-align: center;">
  <a href="https://zebrunner.com/"><img alt="Zebrunner" src="https://github.com/zebrunner/zebrunner/raw/master/docs/img/zebrunner_intro.png"></a>
</p>

> Follow the installation and configuration guide in [MCloud](https://github.com/zebrunner/mcloud) to reuse this image effectively.

## Build

```bash
make build VERSION=3.1   # public.ecr.aws/zebrunner/mcloud-grid:3.1 in the local docker
make push VERSION=3.1    # built by docker buildx and pushed: docker login public.ecr.aws first
```

`make build` builds the image for the platform of this machine, `make push` builds `linux/amd64` and `linux/arm64` (`PLATFORMS`) with `docker buildx` and pushes them as one multi-platform image. `IMAGE_REPO` sets another repository (e.g. `IMAGE_REPO=registry.example.com/mcloud-grid`), `VERSION` is the tag and the
`org.opencontainers.image.version` label (`dev` by default, required by `push`). `make push` uses the buildx builder
`mcloud` (docker-container driver), which it creates once; another platform than the one of the machine needs QEMU
(`docker run --privileged --rm tonistiigi/binfmt --install all`).

The image build compiles and packages the hub; tests and linters run separately (see [Development](#development)).

## Run

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
| --- | --- | --- |
| `GRID_NEW_SESSION_WAIT_TIMEOUT` | `600000` | How long a new session request waits in the queue, ms |
| `GRID_TIMEOUT` | `150` | Client inactivity timeout of a session, s; it replaces the one of the nodes (`0` keeps the one of the nodes) |
| `GRID_BROWSER_TIMEOUT` | `900` | Timeout of a command on the node, s (0 = none); it replaces the one of the nodes (zebrunner/appium sends `0`): a hung command fails after it and the device is released, instead of staying busy until the restart of the hub |
| `GRID_CLEAN_UP_CYCLE` | `5000` | How often the hub checks timed out sessions, ms |
| `GRID_THROW_ON_CAPABILITY_NOT_PRESENT` | `true` | Reject requests no registered device can serve; see [Queueing](#queueing) |
| `GRID_JETTY_MAX_THREADS` | `-1` | Jetty threads of the hub (-1 = default) |
| `GRID_DEBUG` | `false` | Debug logging of the hub |
| `GRID_PROXY`, `GRID_CAPABILITY_MATCHER` | mobile proxy and matcher | Classes of the node proxy and capability matcher; a node names the class of its proxy itself, the hub registers only `MobileRemoteProxy` (and its subclasses), see [Node registration](#node-registration) |
| `JAVA_HEAP_OPTS` | `-Xms1G -Xmx4G` | JVM heap of the hub |
| `JAVA_OPTS` | | Other JVM options |
| `JAVA_HOME`, `PATH`, `LANG` | `/opt/java/openjdk`, its `bin` first, `C.UTF-8` | The Java runtime of the image (Temurin 11, the modules of the hub only) and the UTF-8 locale |
| `SE_OPTS` | | Extra hub options, e.g. `-debug`; `-servlets` replaces the [servlets](#endpoints) of the hub config |
| `CHECK_NODE_REACHABILITY` | `true` | Reject the registration of a node the hub cannot connect to |
| `NODE_REACHABILITY_TIMEOUT` | `2` | Connection timeout of that check, s |
| `NODE_REGISTRATION_TOKEN` | | The secret a node registers with and the hub sends to the nodes, see [Node registration](#node-registration); not set = any node (with a warning in the log) |
| `NODE_ALLOWED_NETWORKS` | | Comma separated CIDR ranges or IPs nodes may register from, e.g. `10.0.0.0/8, 192.168.1.15`; the address a node registers with is checked; not set = any node (an invalid value rejects every node) |
| `MAX_NEW_COMMAND_TIMEOUT` | | Upper limit of `appium:newCommandTimeout`, s; bigger and disabled (`0`) values are limited (no limit when not set) |
| `MCLOUD_LOG_LEVEL` | `INFO` | Log level of the grid code, `FINE` for details; Selenium logs stay as they are |

### STF and device health

STF integration is enabled when both `STF_URL` and `STF_TOKEN` are set.

| Env var | Default | Meaning |
| --- | --- | --- |
| `STF_URL` | | STF address |
| `STF_TOKEN` | | Access token of the STF user that reserves devices for automation; when the user is an STF admin, a device that does not answer the reservation is also marked unhealthy in STF |
| `STF_TIMEOUT` | `3600` | Reservation timeout of a device in STF, s |
| `CHECK_APPIUM_STATUS` | `false` | Check `/status-adb` (Android) or `/status-wda` (iOS) of the node before a session |
| `APPIUM_STATUS_TIMEOUT` | `10` | How long the hub waits for the Appium status check, s; zebrunner/appium gives WebDriverAgent 3 s and a second attempt, adb 5 s |
| `UNHEALTHY_MOBILE_TIMEOUT` | `60` | A device failing the Appium status check is skipped for, s |
| `INACTIVITY_RELEASE_TIMEOUT` | `60` | A device whose session timed out before it started is skipped for, s |
| `STF_DEVICE_INVALID_RESPONSE_IGNORE_TIMEOUT` | `600` | A device with an invalid STF status or failed reservation is skipped for, s |
| `STF_DEVICE_UNAUTHORIZED_IGNORE_TIMEOUT` | `600` | A device unauthorized in STF is skipped for, s |
| `STF_DEVICE_UNHEALTHY_IGNORE_TIMEOUT` | `60` | An unhealthy or not ready device is skipped for, s |
| `STF_DEVICE_MANUALLY_RESERVED_TIMEOUT` | `180` | A device reserved in STF by another user is skipped for, s |

### Capabilities

| Capability | Meaning |
| --- | --- |
| `platformName` | `Android`, `iOS`, ... |
| `appium:platformVersion` | Exact (`13`), range (`11-13`), minimum (`12+`) or list (`12,14`); `7` matches `7.0` |
| `appium:deviceName`, `appium:udid` | One value or a comma separated list |
| `zebrunner:deviceType` | `phone`, `tablet`, `tv`, `tvOS`, ...; `tvOS` devices get `platformName=tvOS` |
| `zebrunner:STF_TOKEN` | Personal STF token: the device is reserved as that user and is not returned after the session; the hub does not pass it to Appium |
| `zebrunner:STF_TIMEOUT` | Reservation timeout in STF for this session, s |
| `zebrunner:enableAdb` | Android: wait for the remote ADB connection of STF before the session |

The node capabilities of the reserved device are passed to the session as `zebrunner:slotCapabilities`.

### Node registration

A node registers with the hub through `/grid/register`, which answers anyone who reaches the hub, and gets the sessions of its device with their commands and capabilities. So the hub registers a node only when:

- its proxy is `MobileRemoteProxy` (the registry of the hub config, `MobileGridRegistry`, rejects the others): a node with another class would skip the checks below;
- it sends `NODE_REGISTRATION_TOKEN` of the hub in `custom` of its configuration (`"custom": {"registrationToken": "<token>"}`), which [zebrunner/appium](https://github.com/zebrunner/appium) does with its own `NODE_REGISTRATION_TOKEN`; the hub takes the token out of the configuration it keeps, so the console, `/grid/api/proxy` and the logs do not show it;
- its address is in `NODE_ALLOWED_NETWORKS` and reachable (`CHECK_NODE_REACHABILITY`), and its device is in STF.

A node that registers again at the same address (e.g. after the hub restarted) is checked for the token only; a node with the id of a registered one at another address is checked as a new node. A rejected node is logged with the reason once in 10 minutes. Without `NODE_REGISTRATION_TOKEN` the hub logs a warning on start: any host that reaches it can register a node. Generate the token with e.g. `openssl rand -hex 24` and set it for the hub and for every node (`NODE_REGISTRATION_TOKEN` of mcloud-agent); a node without it is not registered until it has the token.

The same token authenticates the hub to the nodes: every request of the hub to a node (the commands of the sessions, the status polling, the Appium status checks) carries it in the header `X-MCloud-Node-Token`, and zebrunner/appium with `NODE_REGISTRATION_TOKEN` answers only such requests and the ones of its own container. So the Appium of a device is used through the hub only, which reserves the device in STF.

The hub does not pass `zebrunner:STF_TOKEN` of a session to the node, so it is in no log of Appium; the hub masks it (and the registration token) in its own logs, e.g. the session requests Selenium logs with `-debug`.

`/lifecycle-manager` of Selenium, which shuts the hub down for anyone, is turned off in the hub config (`withoutServlets`).

### Matching

The hub matches the queued requests to the devices in the order of the queue: it claims a free matching device for a request, then prepares it (the Appium status check of `CHECK_APPIUM_STATUS`, the reservation in STF) without the lock of the registry, the devices of several requests in parallel. A slow STF or node delays only its request, not the queueing, the end of the sessions and the registration of the nodes. A device that fails a request (not ready, not reserved in STF) is skipped by this request for 10 s, the request gets the next device at once. A device prepared for a request that is gone meanwhile (timed out) or on a node lost meanwhile is returned in STF.

When the hub loses a node (it is down for `unregisterIfStillDownAfter` of the node, or registers again) its sessions end and their devices are returned in STF. An invalid `zebrunner:STF_TIMEOUT` (not a whole number of seconds) is logged and the default timeout is used.

### Queueing

With `GRID_THROW_ON_CAPABILITY_NOT_PRESENT=true` a request for a device that is not registered fails at once (`cannot find : Capabilities {...}`, or `Empty pool of VM for setup` when no device is registered at all). With `false` it waits in the queue for up to `GRID_NEW_SESSION_WAIT_TIMEOUT` and gets a device that registers meanwhile (a restarted device, a new emulator), otherwise it fails with `Request timed out waiting for a node to become available`.

## Endpoints

| Endpoint | Content |
| --- | --- |
| `/grid/console` | Selenium grid console, with the UDID of every device; the values of the nodes and of the queued requests are escaped, the secrets of the queued requests are masked |
| `/grid/admin/DevicesServlet` | Devices as JSON: platform, type, node, status `free`/`busy`/`ignored`/`down`, why and until when a device is ignored, its session (Appium session id, start, inactivity, last command) |
| `/grid/admin/AllSessionsServlet` | Active sessions as JSON with the requested capabilities and the device; secrets are masked as `*****`: `zebrunner:STF_TOKEN`, `remoteURL` of `zebrunner:slotCapabilities` (the remote ADB address), capabilities named like a token, password, secret, key or credential, and the user info and such query parameters of URLs (e.g. `appium:app`) |
| `/grid/admin/MetricsServlet` | Prometheus metrics: `mcloud_grid_devices{platform,status}`, `mcloud_grid_sessions`, `mcloud_grid_new_session_requests` (the queue) |
| `POST /grid/admin/TerminateSessionServlet?udid=<udid>` or `?sessionId=<id>` | Terminates the session of a device and releases it in STF, see below |
| `/wd/hub/status` | Hub status, also the healthcheck of the image |

### Terminating a session

```bash
curl -X POST -H "Authorization: Bearer <STF access token>" "http://grid:4444/grid/admin/TerminateSessionServlet?udid=emulator-5554"
```

The hub releases the device in STF with the given key first and terminates the session only when STF allows it. STF releases a device for the user it is reserved by or for an STF admin. Devices are reserved by the grid user (`STF_TOKEN`), so the key of a regular STF user may not work (`403`, the session keeps running) unless the session was started with that user's `zebrunner:STF_TOKEN`; an STF admin key works. Without the STF integration the endpoint answers `501`.

The servlets are registered in the hub config by `generate_config`. `com.zebrunner.mcloud.grid.servlets.ProxyInfo` (registration requests of the nodes) is not registered by default.

## Logs

`docker logs mcloud-grid` outputs JSON Lines: one JSON object per event, including Selenium, grid and startup/shutdown logs. Common fields are `timestamp` (UTC), `level`, `logger`, `component`, `category` and `message`. Device events also have `udid` and `sessionId` (the hub's internal session ID):

```json
{"timestamp":"2026-10-09T12:00:00Z","level":"INFO","logger":"com.zebrunner.mcloud.grid.MobileRemoteProxy","component":"mcloud-grid","category":"device","udid":"emulator-5554","sessionId":"0f85...","message":"Device 'Pixel 7' (ANDROID 14) is selected for the session, starting the Appium session."}
```

Use `docker logs mcloud-grid 2>&1 | jq -c 'select(.udid == "emulator-5554")'` to see one device, `select(.category == "grid")` for hub-wide events or `select(.component == "selenium")` for Selenium itself. Selenium's own records have no device ID unless Selenium supplies it. Exceptions are escaped into one JSON field. `MCLOUD_LOG_LEVEL=FINE` adds grid details (skipped devices, STF device data, remoteConnect steps); `SE_OPTS=-debug` turns on Selenium's debug logs without changing the grid log level; the registration requests of the nodes it logs have the registration token masked. The generated config remains available at `/opt/selenium/config.json`, but is not dumped to logs.

For interactive viewing of history and live logs with [lnav](https://lnav.org/):

```bash
docker logs --follow --timestamps mcloud-grid 2>&1 | lnav
```

## Development

Requirements: docker, python 3 (for the linters), hadolint on macOS (`brew install hadolint`). `tests/mvn.sh` runs the pinned Maven + JDK toolchain in `maven:3.9.16-eclipse-temurin-11`, so host-wide `mvn` and Java installs are optional.

```bash
make check   # everything CI runs
make lint    # yaml, GitHub workflows, Dockerfile, markdown, shell scripts, the text files, checkstyle, spotbugs
make test    # unit and STF integration tests, coverage in target/site/jacoco
make docker  # docker image checks: user, config generation, options, endpoints, graceful shutdown
make load    # short load test of the image with fake Appium nodes
```

`tests/lint.sh` and `tests/docker_test.sh` write JUnit XML into `$JUNIT_DIR` when it is set, maven writes it into `target/surefire-reports`. Tests with STF enabled (TestNG group `stf`) run in their own JVM against a WireMock STF stub. Load tests against real grids are described in [LOAD_TESTING.md](LOAD_TESTING.md). CI (`.github/workflows/ci.yml`) runs the same checks on pushes and pull requests: jobs `lint`, `tests` and `docker` (the image checks and a load test with fake Appium nodes); dependabot proposes updates weekly.

The conventions of the style, the formatting and the tooling of this repository are in [CONVENTIONS.md](CONVENTIONS.md), the same in all the MCloud repositories.

## Support

- [Zebrunner PRO](https://zebrunner.com)
- [Zebrunner CE](https://zebrunner.github.io/community-edition)
- [Zebrunner Reporting](https://zebrunner.com/documentation)
- [Carina Guide](http://zebrunner.github.io/carina)
- [Demo Project](https://github.com/zebrunner/carina-demo)
- [Telegram Channel](https://t.me/zebrunner)

## License

[Apache License 2.0](LICENSE)
