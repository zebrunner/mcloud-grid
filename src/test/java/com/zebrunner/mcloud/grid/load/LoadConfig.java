package com.zebrunner.mcloud.grid.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;

/**
 * Load test settings, read from system properties (-Dname=value). See LOAD_TESTING.md.
 */
final class LoadConfig {
    /** Grid endpoint, e.g. http://grid:4444/wd/hub. */
    final String gridUrl;
    /** Total number of sessions to create. */
    final int sessions;
    /** Number of sessions running in parallel. */
    final int concurrency;
    /** Requested capabilities (JSON object, inline or @path/to/file.json). */
    final String capabilities;
    /** Number of light commands (GET /session/{id}/timeouts) to send in every session. */
    final int commandsPerSession;
    /** When set, send commands throughout this duration instead of a fixed count. */
    final Duration commandDuration;
    /** Delay between commands in duration mode. */
    final Duration commandInterval;
    /** How long to keep every session open after the commands. */
    final Duration sessionHold;
    /** Client-side timeout for a new session request (grid may queue it while devices are busy). */
    final Duration newSessionTimeout;
    /** Allowed ratio of failed sessions, 0..1. */
    final double maxErrorRate;
    /** Optional upper bound for p95 of session creation, ms (0 = not checked). */
    final long maxP95CreateMs;
    /** Number of in-process fake Appium nodes to register in the hub (0 = use real devices). */
    final int fakeNodes;
    /** Host name the hub uses to reach fake nodes (host.docker.internal when the hub runs in Docker Desktop). */
    final String fakeNodesHost;
    /** Platform of the fake nodes: ANDROID or IOS. */
    final String fakeNodesPlatform;
    /** Artificial latency of fake node session creation, ms. */
    final long fakeNodeSessionDelayMs;
    /** Port of the in-process fake STF the hub uses (STF_URL of the hub must point to it), 0 = no fake STF. */
    final int fakeStfPort;
    /** Latency of every fake STF response, ms. */
    final long fakeStfLatencyMs;
    /** Directory for the JSON report. */
    final String reportDir;
    /** Preemptive Authorization header for every WebDriver request; never print this value. */
    final String authorization;

    private LoadConfig() {
        this(System.getenv("LOAD_GRID_USERNAME"), System.getenv("LOAD_GRID_PASSWORD"));
    }

    private LoadConfig(String username, String password) {
        gridUrl = stripTrailingSlash(System.getProperty("grid.url", ""));
        sessions = Integer.getInteger("load.sessions", 20);
        concurrency = Integer.getInteger("load.concurrency", 5);
        capabilities = readCapabilities(System.getProperty("load.caps", "{\"platformName\":\"Android\"}"));
        commandsPerSession = Integer.getInteger("load.commands", 3);
        commandDuration = Duration.ofMillis(Long.getLong("load.commandDurationMs", 0L));
        commandInterval = Duration.ofMillis(Long.getLong("load.commandIntervalMs", 1000L));
        sessionHold = Duration.ofMillis(Long.getLong("load.sessionHoldMs", 0L));
        newSessionTimeout = Duration.ofSeconds(Long.getLong("load.newSessionTimeoutSec", 600L));
        maxErrorRate = Double.parseDouble(System.getProperty("load.maxErrorRate", "0"));
        maxP95CreateMs = Long.getLong("load.maxP95CreateMs", 0L);
        fakeNodes = Integer.getInteger("load.fakeNodes", 0);
        fakeNodesHost = System.getProperty("load.fakeNodes.host", "localhost");
        fakeNodesPlatform = System.getProperty("load.fakeNodes.platform", "ANDROID");
        fakeNodeSessionDelayMs = Long.getLong("load.fakeNodes.sessionDelayMs", 0L);
        fakeStfPort = Integer.getInteger("load.fakeStf.port", 0);
        fakeStfLatencyMs = Long.getLong("load.fakeStf.latencyMs", 0L);
        reportDir = System.getProperty("load.reportDir", "target/load-report");
        authorization = basicAuthorization(username, password);
        if (sessions <= 0 || concurrency <= 0 || commandsPerSession < 0 || commandDuration.isNegative()
                || commandInterval.isZero() || commandInterval.isNegative() || sessionHold.isNegative() || newSessionTimeout.isZero()
                || newSessionTimeout.isNegative()) {
            throw new IllegalArgumentException("Sessions, concurrency, timeouts and command interval must be positive; command count and duration cannot be negative");
        }
    }

    static LoadConfig fromSystemProperties() {
        return new LoadConfig();
    }

    static LoadConfig fromSystemProperties(String username, String password) {
        return new LoadConfig(username, password);
    }

    /** Hub root (without /wd/hub), used for node registration. */
    String hubRoot() {
        return gridUrl.endsWith("/wd/hub") ? gridUrl.substring(0, gridUrl.length() - "/wd/hub".length()) : gridUrl;
    }

    private static String stripTrailingSlash(String url) {
        if (!url.isEmpty() && java.net.URI.create(url).getRawUserInfo() != null) {
            throw new IllegalArgumentException("Do not put credentials in grid.url; use LOAD_GRID_USERNAME and LOAD_GRID_PASSWORD");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static String basicAuthorization(String username, String password) {
        if (username == null && password == null) {
            return null;
        }
        if (username == null || password == null || username.isEmpty() || username.contains(":")) {
            throw new IllegalArgumentException("Set both LOAD_GRID_USERNAME and LOAD_GRID_PASSWORD (username must not contain ':')");
        }
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String readCapabilities(String value) {
        if (!value.startsWith("@")) {
            return value;
        }
        try {
            return Files.readString(Paths.get(value.substring(1)));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read capabilities file: " + value, e);
        }
    }

    @Override
    public String toString() {
        return "gridUrl=" + gridUrl + ", sessions=" + sessions + ", concurrency=" + concurrency + ", commands=" + commandsPerSession
                + ", commandDuration=" + commandDuration.toMillis() + "ms, commandInterval=" + commandInterval.toMillis()
                + "ms, hold=" + sessionHold.toMillis() + "ms, fakeNodes=" + fakeNodes
                + (fakeStfPort > 0 ? ", fakeStf=:" + fakeStfPort + " (" + fakeStfLatencyMs + "ms latency)" : "") + ", caps=[redacted]";
    }
}
