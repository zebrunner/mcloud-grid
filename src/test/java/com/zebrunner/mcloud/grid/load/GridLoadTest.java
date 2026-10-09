package com.zebrunner.mcloud.grid.load;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load/performance scenario: creates many real or fake sessions in parallel to measure
 * grid throughput, latency, and failure rate under stress.
 */
@Test(groups = "load")
public class GridLoadTest {
    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);

    private LoadConfig config;
    private final List<FakeAppiumNode> fakeNodes = new ArrayList<>();
    private FakeStf fakeStf;
    private HttpClient http;
    private ExecutorService httpExecutor;

    public GridLoadTest() {
        // TestNG discovers the class before setup; load user configuration in setUp for actionable errors.
    }

    GridLoadTest(LoadConfig config) {
        this.config = config;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        if (config == null) {
            config = LoadConfig.fromSystemProperties();
        }
        if (config.gridUrl.isEmpty()) {
            throw new SkipException("Set -Dgrid.url=http://host:4444/wd/hub to run load tests");
        }
        httpExecutor = Executors.newFixedThreadPool(Math.max(4, config.concurrency));
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(httpExecutor)
                .build();
        System.out.println("[load] " + config);
        if (config.fakeStfPort > 0) {
            fakeStf = new FakeStf(config.fakeStfPort, config.fakeStfLatencyMs);
        }
        for (int i = 0; i < config.fakeNodes; i++) {
            FakeAppiumNode node = new FakeAppiumNode("fake-" + i + "-" + System.nanoTime(), config.fakeNodesPlatform, config.fakeNodesHost,
                    config.fakeNodeSessionDelayMs);
            fakeNodes.add(node);
            if (fakeStf != null) {
                fakeStf.addDevice(node.udid());
            }
            node.register(config.hubRoot(), config.authorization);
        }
        if (!fakeNodes.isEmpty()) {
            // the hub adds a node asynchronously after registration
            Thread.sleep(3000);
            System.out.println("[load] registered " + fakeNodes.size() + " fake nodes");
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        fakeNodes.forEach(FakeAppiumNode::close);
        if (fakeStf != null) {
            fakeStf.close();
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    public void sessionLifecycleUnderLoad() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(config.concurrency);
        List<Future<LoadReport.SessionResult>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < config.sessions; i++) {
                futures.add(pool.submit(this::runSession));
            }
            List<LoadReport.SessionResult> results = new ArrayList<>();
            for (Future<LoadReport.SessionResult> future : futures) {
                results.add(future.get());
            }
            LoadReport report = new LoadReport(results, (System.nanoTime() - start) / 1_000_000,
                    fakeStf == null ? Map.of() : fakeStf.requests());
            Path file = report.write(config);
            System.out.println("[load] report: " + file.toAbsolutePath());
            System.out.println(report.toJson(config));

            Assert.assertTrue(report.errorRate() <= config.maxErrorRate,
                    String.format("Error rate %.2f%% exceeds %.2f%%: %s", report.errorRate() * 100, config.maxErrorRate * 100, report.errors()));
            if (config.maxP95CreateMs > 0) {
                long p95 = LoadReport.percentile(report.createLatencies(), 95);
                Assert.assertTrue(p95 <= config.maxP95CreateMs, "p95 of session creation " + p95 + "ms exceeds " + config.maxP95CreateMs + "ms");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private LoadReport.SessionResult runSession() {
        List<Long> commandMs = new ArrayList<>();
        long createMs = -1;
        String sessionId = null;
        try {
            long t0 = System.nanoTime();
            HttpResponse<String> created = send("POST", "/session", newSessionBody(), config.newSessionTimeout);
            createMs = (System.nanoTime() - t0) / 1_000_000;
            Matcher matcher = SESSION_ID.matcher(created.body());
            if (created.statusCode() != 200 || !matcher.find()) {
                return new LoadReport.SessionResult(false, "create: HTTP " + created.statusCode() + " " + errorOf(created.body()), createMs, commandMs, -1);
            }
            sessionId = matcher.group(1);

            long deadline = System.nanoTime() + config.commandDuration.toNanos();
            int commandCount = 0;
            while (config.commandDuration.isZero() ? commandCount < config.commandsPerSession : System.nanoTime() < deadline) {
                long c0 = System.nanoTime();
                HttpResponse<String> response = send("GET", "/session/" + sessionId + "/timeouts", null, COMMAND_TIMEOUT);
                commandMs.add((System.nanoTime() - c0) / 1_000_000);
                commandCount++;
                if (response.statusCode() != 200) {
                    return new LoadReport.SessionResult(false, "command: HTTP " + response.statusCode() + " " + errorOf(response.body()),
                            createMs, commandMs, deleteQuietly(sessionId));
                }
                if (!config.commandDuration.isZero()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) {
                        TimeUnit.NANOSECONDS.sleep(Math.min(config.commandInterval.toNanos(), remaining));
                    }
                }
            }
            if (!config.sessionHold.isZero()) {
                Thread.sleep(config.sessionHold.toMillis());
            }
            long d0 = System.nanoTime();
            HttpResponse<String> deleted = send("DELETE", "/session/" + sessionId, null, COMMAND_TIMEOUT);
            long deleteMs = (System.nanoTime() - d0) / 1_000_000;
            if (deleted.statusCode() != 200) {
                return new LoadReport.SessionResult(false, "delete: HTTP " + deleted.statusCode() + " " + errorOf(deleted.body()), createMs, commandMs, deleteMs);
            }
            return new LoadReport.SessionResult(true, null, createMs, commandMs, deleteMs);
        } catch (InterruptedException e) {
            if (sessionId != null) {
                deleteQuietly(sessionId);
            }
            Thread.currentThread().interrupt();
            return new LoadReport.SessionResult(false, "interrupted", createMs, commandMs, -1);
        } catch (Exception e) {
            if (sessionId != null) {
                deleteQuietly(sessionId);
            }
            return new LoadReport.SessionResult(false, e.getClass().getSimpleName() + ": " + e.getMessage(), createMs, commandMs, -1);
        }
    }

    private long deleteQuietly(String sessionId) {
        long d0 = System.nanoTime();
        try {
            send("DELETE", "/session/" + sessionId, null, COMMAND_TIMEOUT);
        } catch (Exception ignored) {
            // best effort cleanup
        }
        return (System.nanoTime() - d0) / 1_000_000;
    }

    private String newSessionBody() {
        // both W3C and legacy JSON wire protocol payloads, as Selenium 3 clients send them
        return "{\"capabilities\":{\"alwaysMatch\":" + config.capabilities + ",\"firstMatch\":[{}]},\"desiredCapabilities\":" + config.capabilities + "}";
    }

    private HttpResponse<String> send(String method, String path, String body, Duration timeout) throws Exception {
        return http.send(request(config.gridUrl + path, method, body, timeout, config.authorization), HttpResponse.BodyHandlers.ofString());
    }

    static HttpRequest request(String url, String method, String body, Duration timeout, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return request.build();
    }

    private static String errorOf(String body) {
        Matcher message = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{0,120})").matcher(body);
        return message.find() ? message.group(1) : body.substring(0, Math.min(120, body.length()));
    }
}
