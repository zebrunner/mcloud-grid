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

The file `VERSION` is the release in work (bumped by hand after a release, CONVENTIONS.md#versions). The `publish` job of CI pushes the image of every merge into `develop` after all the checks passed: `public.ecr.aws/zebrunner/mcloud-grid:<VERSION>-develop-<sha>` (7 characters of the commit) and `<VERSION>-develop-latest`, the newest commit of `develop`.

## Run

```bash
docker run -d -p 4444:4444 -e GRID_NEW_SESSION_WAIT_TIMEOUT=240000 \
  -e GRID_TIMEOUT=60 -e GRID_BROWSER_TIMEOUT=60 \
  -e STF_URL=http://stf.example.com -e STF_TOKEN=<token> \
  --name mcloud-grid zebrunner/mcloud-grid:latest
```

The hub runs as an unprivileged user and stops gracefully on `docker stop`. `STF_URL` and `STF_TOKEN` (the access token of an STF admin) are required: the nodes register with their node tokens, which the hub verifies with STF (see [Node registration](#node-registration)); without them the hub starts, logs `STF_URL and STF_TOKEN are not set` (`SEVERE`) and refuses every node, as on the first start of MCloud before an admin of STF has an access token.

## Configuration

### Grid

| Env var | Default | Meaning |
| --- | --- | --- |
| `GRID_NEW_SESSION_WAIT_TIMEOUT` | `600000` | How long a new session request waits in the queue, ms |
| `GRID_TIMEOUT` | `150` | Client inactivity timeout of a session, s; it replaces the one of the nodes (`0` keeps the one of the nodes) |
| `GRID_BROWSER_TIMEOUT` | `900` | Timeout of a command on the node, s (0 = none); it replaces the one of the nodes (zebrunner/appium sends `0`): a hung command fails after it and the device is released, instead of staying busy until the restart of the hub |
| `GRID_CLEAN_UP_CYCLE` | `5000` | How often the hub checks timed out sessions, ms; it replaces the one of the nodes (zebrunner/appium sends `5000`) |
| `GRID_THROW_ON_CAPABILITY_NOT_PRESENT` | `true` | Reject requests no registered device can serve; see [Queueing](#queueing) |
| `GRID_JETTY_MAX_THREADS` | `-1` | Jetty threads of the hub (-1 = default) |
| `GRID_DEBUG` | `false` | Debug logging of the hub |
| `GRID_CAPABILITY_MATCHER` | `com.zebrunner.mcloud.grid.MobileCapabilityMatcher` | Class of the capability matcher; the class of the proxy is named by every node itself, the hub registers only `MobileRemoteProxy` (and its subclasses), see [Node registration](#node-registration); `GRID_PROXY` of the older images is ignored |
| `JAVA_HEAP_OPTS` | `-Xms1G -Xmx4G` | JVM heap of the hub |
| `JAVA_OPTS` | | Other JVM options |
| `JAVA_HOME`, `PATH`, `LANG` | `/opt/java/openjdk`, its `bin` first, `C.UTF-8` | The Java runtime of the image (Temurin 11, the modules of the hub only) and the UTF-8 locale |
| `SE_OPTS` | | Extra hub options, e.g. `-debug`; `-servlets` replaces the [servlets](#endpoints) of the hub config |
| `CHECK_NODE_REACHABILITY` | `true` | Reject the registration of a node the hub cannot connect to |
| `NODE_REACHABILITY_TIMEOUT` | `2` | Connection timeout of that check, s |
| `NODE_ALLOWED_NETWORKS` | | Comma separated CIDR ranges or IPs nodes may register from, e.g. `10.0.0.0/8, 192.168.1.15`; the address a node registers with is checked; not set = any node (an invalid value rejects every node) |
| `MAX_NEW_COMMAND_TIMEOUT` | | Upper limit of `appium:newCommandTimeout`, s; bigger and disabled (`0`) values are limited (no limit when not set) |
| `PLAYWRIGHT_ENABLED` | `false` | Run the Playwright sessions: the option of the farm, see [Playwright](#playwright) |
| `PLAYWRIGHT_IDLE_TIMEOUT` | `120` | A side of a Playwright WebSocket that answers no ping for it is closed with the other side, s; see [Playwright](#playwright) |
| `MCLOUD_LOG_LEVEL` | `INFO` | Log level of the grid code, `FINE` for details; Selenium logs stay as they are |

The variables of the hub config (`GRID_*`) are validated before the hub starts, see [Exit codes and health](#exit-codes-and-health): the numbers are whole numbers (`GRID_NEW_SESSION_WAIT_TIMEOUT` and `GRID_JETTY_MAX_THREADS` at least `-1`, `GRID_TIMEOUT` and `GRID_BROWSER_TIMEOUT` at least `0`, `GRID_CLEAN_UP_CYCLE` at least `1`), the booleans `true` or `false`.

### STF and device health

`STF_URL` and `STF_TOKEN` are required for the nodes: without them the hub starts but refuses every node.

| Env var | Default | Meaning |
| --- | --- | --- |
| `STF_URL` | | Required: STF address |
| `STF_TOKEN` | | Required: access token of the STF admin that reserves devices for automation and verifies the node tokens of the nodes with the node registry of STF; a device that does not answer the reservation is also marked unhealthy in STF |
| `STF_TIMEOUT` | `3600` | Reservation timeout of a device in STF, s |
| `CHECK_APPIUM_STATUS` | `false` | Check `/status-adb` (Android) or `/status-wda` (iOS) of the node before a session |
| `APPIUM_STATUS_TIMEOUT` | `10` | How long the hub waits for the Appium status check, s; zebrunner/appium gives WebDriverAgent 3 s and a second attempt, adb 5 s |
| `UNHEALTHY_MOBILE_TIMEOUT` | `60` | A device failing the Appium status check is skipped for, s |
| `INACTIVITY_RELEASE_TIMEOUT` | `60` | A device whose session timed out before it started is skipped for, s |
| `STF_DEVICE_INVALID_RESPONSE_IGNORE_TIMEOUT` | `600` | A device with an invalid STF status or failed reservation is skipped for, s |
| `STF_DEVICE_UNAUTHORIZED_IGNORE_TIMEOUT` | `600` | A device unauthorized in STF is skipped for, s |
| `STF_DEVICE_UNHEALTHY_IGNORE_TIMEOUT` | `60` | An unhealthy or not ready device is skipped for, s |
| `STF_DEVICE_MANUALLY_RESERVED_TIMEOUT` | `180` | A device reserved in STF by another user is skipped for, s |
| `STF_DEVICE_LOW_BATTERY_THRESHOLD` | `15` | A present and ready device charged below this level (%) in STF (Android and iOS) is skipped by automation; `0` disables the check |
| `STF_DEVICE_LOW_BATTERY_IGNORE_TIMEOUT` | `3600` | A device with a low battery is skipped for, s; it is checked again by the next request after that |

A request that leaves the queue without a device (`newSessionWaitTimeout` or a gone client) is logged as a warning with the
matching devices: busy, down and ignored ones with the reason (e.g. `low battery: 12% (discharging), below 15%`).

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
- it sends its node token in `custom` of its configuration (`"custom": {"registrationToken": "<node token>"}`), which [zebrunner/appium](https://github.com/zebrunner/appium) does with its `NODE_REGISTRATION_TOKEN` (`MCLOUD_NODE_TOKEN` of [mcloud-agent](https://github.com/zebrunner/mcloud-agent)), and STF verifies the token for the address of the node; the hub takes the token out of the configuration it keeps, so the console, `/grid/api/proxy` and the logs do not show it;
- its address is in `NODE_ALLOWED_NETWORKS` (checked before the request to STF) and reachable (`CHECK_NODE_REACHABILITY`), and its device is in STF.

A node gets its node token `mcn1.<label>.<secret>` when it joins the farm with an invite of the admin (`./zebrunner.sh node-invite <label> <address>` of [mcloud](https://github.com/zebrunner/mcloud) on the server, `./zebrunner.sh join` of mcloud-agent on the node): STF keeps the node (its label and the address of the invite) in its node registry, and only the SHA-256 of the token. The hub accepts a node token only when STF verifies it for the host of the node URL: `POST /api/v1/nodes/verify` with the token and the host, authorized by `STF_TOKEN`, answers `200` only for an active node whose registered address is that host. The answers of STF (`200` and `403`) are cached for 30 s by the SHA-256 of the token and the host, so the retries of a rejected node do not load STF.

- The hub sends the node token of a node only to that node, in the header `X-MCloud-Node-Token` of its requests (the commands of the sessions, the status polling, the Appium status checks); zebrunner/appium answers only such requests and the ones of its own container. So the Appium of a device is used through the hub only, which reserves the device in STF.
- STF verifies the node token of every registered node again every 60 s, without the cache: a node whose token it rejects (`403`: the node is revoked, joined again with a new token, or its address is another one) is unregistered at once, its sessions end and their devices are returned in STF. While STF does not answer, the node is kept for up to 10 minutes since its last verification, then it is unregistered too. The logs name the node by its label, never by its token.
- A rejected node gets the reason in the answer of `/grid/register` (zebrunner/appium logs it), and the hub logs it once in 10 minutes; see [Troubleshooting](#troubleshooting). The messages never contain a token.

A node that registers again at the same address (e.g. after the hub restarted) is checked for its token only; a node with the id of a registered one at another address is checked as a new node.

The hub does not pass `zebrunner:STF_TOKEN` of a session to the node, so it is in no log of Appium; the hub masks it, the registration token and any node token (`mcn1.<label>.*****`) in its own logs, e.g. the session requests and the registration requests Selenium logs with `-debug`.

`/lifecycle-manager` of Selenium, which shuts the hub down for anyone, is turned off in the hub config (`withoutServlets`).

### Matching

The hub matches the queued requests to the devices in the order of the queue: it claims a free matching device for a request, then prepares it (the Appium status check of `CHECK_APPIUM_STATUS`, the reservation in STF) without the lock of the registry, the devices of several requests in parallel. A slow STF or node delays only its request, not the queueing, the end of the sessions and the registration of the nodes. A device that fails a request (not ready, not reserved in STF) is skipped by this request for 10 s, the request gets the next device at once. A device prepared for a request that is gone meanwhile (timed out) or on a node lost meanwhile is returned in STF.

When the hub loses a node (it is down for `unregisterIfStillDownAfter` of the node, or registers again) its sessions end and their devices are returned in STF. An invalid `zebrunner:STF_TIMEOUT` (not a whole number of seconds) is logged and the default timeout is used.

### Queueing

With `GRID_THROW_ON_CAPABILITY_NOT_PRESENT=true` a request for a device that is not registered fails at once (`cannot find : Capabilities {...}`, or `Empty pool of VM for setup` when no device is registered at all). With `false` it waits in the queue for up to `GRID_NEW_SESSION_WAIT_TIMEOUT` and gets a device that registers meanwhile (a restarted device, a new emulator), otherwise it fails with `Request timed out waiting for a node to become available`.

### Playwright

A Playwright session is an Appium session of the `Playwright` automation of [zebrunner/appium](https://github.com/zebrunner/appium#playwright) (`appium:automationName: Playwright`): the hub queues it, reserves the device in STF and releases it as any other session. The driver serves the Playwright server of the device on the WebSocket `/mcloud/playwright/<session id>` of the node, `PlaywrightServlet` passes the WebSocket of the client on to it: `ws://hub:4444/grid/admin/PlaywrightServlet/<Appium session id>` (`/mcloud/playwright/<session id>` of MCloud).

- The Playwright sessions are an option of the farm, off by default: `PLAYWRIGHT_ENABLED=true` turns them on. Without it the hub refuses a new session of the `Playwright` automation before the queue (`Playwright sessions are not enabled on this grid`, as a device it does not have: no device is reserved) and the WebSocket with `501`; the other sessions are not affected.
- The hub connects to the node first, with the node token of the node and the headers Playwright checks (`User-Agent`, its version) or uses (`x-playwright-*`), never the other headers of the client: an unknown session is `404`, a session the node does not serve (another automation) is `502`.
- The frames are passed on in parts as they come, a message of any size (screenshots, traces).
- While a WebSocket of the session is open the session does not time out: the client sends no WebDriver commands then. Playwright sends no pings, the hub pings both sides every 30 s; a side that answers nothing for `PLAYWRIGHT_IDLE_TIMEOUT` (a client that is gone without a close) is closed with the other one.
- When the last WebSocket of a session is closed the inactivity of the session counts from then: a session the client did not delete (`DELETE /wd/hub/session/<id>`) is released after `GRID_TIMEOUT` as any session without commands.

## Endpoints

| Endpoint | Content |
| --- | --- |
| `/grid/console` | Selenium grid console, with the UDID of every device; the values of the nodes and of the queued requests are escaped, the secrets of the queued requests are masked |
| `/grid/admin/DevicesServlet` | Devices as JSON: platform, type, node, status `free`/`busy`/`ignored`/`down`, why and until when a device is ignored, its session (Appium session id, start, inactivity, last command) |
| `/grid/admin/AllSessionsServlet` | Active sessions as JSON with the requested capabilities and the device; secrets are masked as `*****`: `zebrunner:STF_TOKEN`, `remoteURL` of `zebrunner:slotCapabilities` (the remote ADB address), capabilities named like a token, password, secret, key or credential, and the user info and such query parameters of URLs (e.g. `appium:app`) |
| `/grid/admin/PlaywrightServlet/<session id>` | The WebSocket of a Playwright session, see [Playwright](#playwright) |
| `/grid/admin/MetricsServlet` | Prometheus metrics: `mcloud_grid_devices{platform,status}`, `mcloud_grid_sessions`, `mcloud_grid_new_session_requests` (the queue) |
| `POST /grid/admin/TerminateSessionServlet?udid=<udid>` or `?sessionId=<id>` | Terminates the session of a device and releases it in STF, see below |
| `/wd/hub/status` | Hub status, also the healthcheck of the image |

### Terminating a session

```bash
curl -X POST -H "Authorization: Bearer <STF access token>" "http://grid:4444/grid/admin/TerminateSessionServlet?udid=emulator-5554"
```

The hub releases the device in STF with the given key first and terminates the session only when STF allows it. STF releases a device for the user it is reserved by or for an STF admin. Devices are reserved by the grid user (`STF_TOKEN`), so the key of a regular STF user may not work (`403`, the session keeps running) unless the session was started with that user's `zebrunner:STF_TOKEN`; an STF admin key works.

The servlets are registered in the hub config by `generate_config`. `com.zebrunner.mcloud.grid.servlets.ProxyInfo` (registration requests of the nodes) is not registered by default.

## Logs

`docker logs mcloud-grid` outputs JSON Lines: one JSON object per event, including Selenium, grid and startup/shutdown logs. Common fields are `timestamp` (UTC), `level`, `logger`, `component`, `category` and `message`. Device events also have `udid` and `sessionId` (the hub's internal session ID):

```json
{"timestamp":"2026-10-09T12:00:00Z","level":"INFO","logger":"com.zebrunner.mcloud.grid.MobileRemoteProxy","component":"mcloud-grid","category":"device","udid":"emulator-5554","sessionId":"0f85...","message":"Device 'Pixel 7' (ANDROID 14) is selected for the session, starting the Appium session."}
```

Use `docker logs mcloud-grid 2>&1 | jq -c 'select(.udid == "emulator-5554")'` to see one device, `select(.category == "grid")` for hub-wide events or `select(.component == "selenium")` for Selenium itself. Selenium's own records have no device ID unless Selenium supplies it. Exceptions are escaped into one JSON field. `MCLOUD_LOG_LEVEL=FINE` adds grid details (skipped devices, STF device data, remoteConnect steps); `SE_OPTS=-debug` turns on Selenium's debug logs without changing the grid log level; the registration requests of the nodes it logs have the node token masked. The generated config remains available at `/opt/selenium/config.json`, but is not dumped to logs.

For interactive viewing of history and live logs with [lnav](https://lnav.org/):

```bash
docker logs --follow --timestamps mcloud-grid 2>&1 | lnav
```

## Troubleshooting

### A node is not registered

The node gets the reason in the answer of `/grid/register` (the log of the appium container of the node), the hub logs it with `[NODE REGISTRATION]`: `docker logs mcloud-grid 2>&1 | grep 'NODE REGISTRATION'`. The node registers again every few seconds, so it is registered as soon as the cause is fixed.

| Message | Cause and fix |
| --- | --- |
| `has no node token` | The node is not joined: `NODE_REGISTRATION_TOKEN` of its appium container is empty. Create an invite on the server (`./zebrunner.sh node-invite <label> <address>`) and join the node with it (`./zebrunner.sh join`), which sets its node token. |
| `has a token that is not a node token` or `has a malformed node token` | The node sends something else than `mcn1.<label>.<secret>` (e.g. a truncated or edited value). Join the node again. |
| `has an unknown or revoked node token` | STF answered `403`: the node is revoked or deleted, joined again elsewhere (a new join replaces the token at once), or the host of the node URL (printed in the message) is not the address of the invite. The host is the address the node registers with (`STF_PROVIDER_HOST` of mcloud-agent) and must be the address of the invite exactly (an IP and a hostname are different addresses). Check the node in STF (`./zebrunner.sh nodes check <label>` of mcloud on the server, `./zebrunner.sh doctor` of mcloud-agent on the node), then join the node again with an invite for the right address. |
| `has a node token, but STF could not verify it (STF did not answer)` | The hub does not reach `STF_URL`: check the address, the STF containers and the network between them. A registered node is kept for 10 minutes without the answers of STF. |
| `has a node token, but STF could not verify it (HTTP 401)` or `(HTTP 404)` | `STF_TOKEN` of the hub is not the token of an STF admin (`401`), or STF has no node registry (`404`): update STF and set the admin token. The hub logs these once in 10 minutes. |
| `is not in NODE_ALLOWED_NETWORKS`, `is not reachable from the hub` | The address of the node is outside `NODE_ALLOWED_NETWORKS`, or the hub cannot connect to it. |
| `Could not find device with udid '...' in STF` | The device of the node is not in STF yet; it registers once STF has it. |

A registered node that disappears with `[NODE TOKEN] STF does not accept the node token of node ... any more` was revoked, joined again or moved: join it again.

### No node registers

`STF_URL and STF_TOKEN are not set` in the log of the start (`SEVERE`) and `STF_URL and STF_TOKEN of the hub are not set` for every node: set both and restart the hub, it verifies the node tokens with STF.

## Exit codes and health

- The entrypoint validates the variables of the hub config (`GRID_*`) first: every invalid one is logged (`SEVERE`, e.g. `GRID_TIMEOUT '1h' is not a whole number of at least 0.`), then the container exits with `0`, so a restart policy does not restart it: a restart does not fix the configuration. Fix the variables and start the container again.
- `1`: `/opt/selenium/config.json` could not be written, a restart may fix it.
- Otherwise the container runs the hub until it stops: `docker stop` stops it gracefully (`shutdown complete` in the log).
- The healthcheck of the image is `GET /wd/hub/status` of the hub every 30 s (timeout 5 s, 3 retries, start period 30 s).

## Development

Requirements: docker, python 3 (for the linters), hadolint on macOS (`brew install hadolint`). `tests/mvn.sh` runs the pinned Maven + JDK toolchain in `maven:3.9.16-eclipse-temurin-11`, so host-wide `mvn` and Java installs are optional.

```bash
make check   # everything CI runs
make lint    # yaml, GitHub workflows, Dockerfile, markdown, shell scripts, the text files, checkstyle, spotbugs
make test    # unit and STF integration tests, coverage in target/site/jacoco
make docker  # docker image checks: user, no start without STF, config generation, options, endpoints, node registration with a fake STF, graceful shutdown
make load    # short load test of the image with fake Appium nodes and a fake STF
```

`tests/lint.sh` and `tests/docker_test.sh` write JUnit XML into `$JUNIT_DIR` when it is set, maven writes it into `target/surefire-reports`. Tests with STF enabled (TestNG group `stf`) run in their own JVM against a WireMock STF stub. Load tests against real grids are described in [LOAD_TESTING.md](LOAD_TESTING.md). CI (`.github/workflows/ci.yml`) runs the same checks on pushes and pull requests: jobs `lint`, `tests` and `docker` (the image checks and a load test with fake Appium nodes and a fake STF); dependabot proposes updates weekly.

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
