package com.zebrunner.mcloud.grid.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

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

    private LoadConfig() {
        gridUrl = stripTrailingSlash(System.getProperty("grid.url", ""));
        sessions = Integer.getInteger("load.sessions", 20);
        concurrency = Integer.getInteger("load.concurrency", 5);
        capabilities = readCapabilities(System.getProperty("load.caps", "{\"platformName\":\"Android\"}"));
        commandsPerSession = Integer.getInteger("load.commands", 3);
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
    }

    static LoadConfig fromSystemProperties() {
        return new LoadConfig();
    }

    /** Hub root (without /wd/hub), used for node registration. */
    String hubRoot() {
        return gridUrl.endsWith("/wd/hub") ? gridUrl.substring(0, gridUrl.length() - "/wd/hub".length()) : gridUrl;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
                + ", hold=" + sessionHold.toMillis() + "ms, fakeNodes=" + fakeNodes
                + (fakeStfPort > 0 ? ", fakeStf=:" + fakeStfPort + " (" + fakeStfLatencyMs + "ms latency)" : "") + ", caps=" + capabilities;
    }
}
