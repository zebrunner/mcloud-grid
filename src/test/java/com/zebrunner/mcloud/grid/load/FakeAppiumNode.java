package com.zebrunner.mcloud.grid.load;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Appium-like node: registers in the hub with MobileRemoteProxy and answers the WebDriver
 * endpoints the hub forwards to it. Lets the hub be stressed without real devices.
 */
final class FakeAppiumNode implements AutoCloseable {
    private static final String JSON = "application/json; charset=utf-8";

    private final HttpServer server;
    private final String udid;
    private final String platform;
    private final long sessionDelayMs;
    private final String advertisedUrl;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger createdSessions = new AtomicInteger();
    private final ScheduledExecutorService reRegistration = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "re-registration");
        thread.setDaemon(true);
        return thread;
    });

    FakeAppiumNode(String udid, String platform, String advertisedHost, long sessionDelayMs) throws IOException {
        this.udid = udid;
        this.platform = platform;
        this.sessionDelayMs = sessionDelayMs;
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/", this::handle);
        this.server.start();
        this.advertisedUrl = "http://" + advertisedHost + ":" + server.getAddress().getPort();
    }

    String udid() {
        return udid;
    }

    int createdSessions() {
        return createdSessions.get();
    }

    /**
     * Registers in the hub and, as Selenium and Appium nodes do, every 5 seconds registers again if the hub does not know the node
     * (e.g. after a restart of the hub). Registering a known node again would terminate its sessions (PROXY_REREGISTRATION).
     */
    void register(String hubRoot, String authorization) throws IOException, InterruptedException {
        registerOnce(hubRoot, authorization);
        reRegistration.scheduleWithFixedDelay(() -> {
            try {
                if (!isRegistered(hubRoot, authorization)) {
                    registerOnce(hubRoot, authorization);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // the next attempt follows
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private boolean isRegistered(String hubRoot, String authorization) throws IOException, InterruptedException {
        String url = hubRoot + "/grid/api/proxy?id=" + java.net.URLEncoder.encode(advertisedUrl, StandardCharsets.UTF_8);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                GridLoadTest.request(url, "GET", null, Duration.ofSeconds(10), authorization), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains("\"success\": true");
    }

    private void registerOnce(String hubRoot, String authorization) throws IOException, InterruptedException {
        String caps = "{\"platformName\":\"" + platform + "\",\"appium:platformVersion\":\"" + ("IOS".equals(platform) ? "17.2" : "13")
                + "\",\"appium:udid\":\"" + udid + "\",\"appium:deviceName\":\"" + udid + "\",\"zebrunner:deviceType\":\"phone\""
                + ",\"maxInstances\":1,\"seleniumProtocol\":\"WebDriver\"}";
        String body = "{\"class\":\"org.openqa.grid.common.RegistrationRequest\",\"name\":\"" + udid + "\",\"configuration\":{"
                + "\"id\":\"" + advertisedUrl + "\",\"remoteHost\":\"" + advertisedUrl + "\",\"url\":\"" + advertisedUrl + "\","
                + "\"proxy\":\"com.zebrunner.mcloud.grid.MobileRemoteProxy\",\"maxSession\":1,\"register\":true,\"registerCycle\":5000,"
                + "\"nodePolling\":2000,\"unregisterIfStillDownAfter\":5000,\"downPollingLimit\":1,"
                + "\"capabilities\":[" + caps + "]}}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                GridLoadTest.request(hubRoot + "/grid/register", "POST", body, Duration.ofSeconds(30), authorization),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Node " + udid + " registration failed: " + response.statusCode() + " " + response.body());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().replaceFirst("^/wd/hub", "");
        exchange.getRequestBody().readAllBytes();
        try {
            if (path.equals("/status") || path.equals("/status-adb") || path.equals("/status-wda")) {
                respond(exchange, 200, "{\"status\":0,\"value\":{\"ready\":true,\"message\":\"fake node\"}}");
            } else if ("POST".equals(method) && path.equals("/session")) {
                sleep(sessionDelayMs);
                String id = UUID.randomUUID().toString();
                sessions.add(id);
                createdSessions.incrementAndGet();
                respond(exchange, 200, "{\"sessionId\":\"" + id + "\",\"status\":0,\"value\":{\"sessionId\":\"" + id
                        + "\",\"capabilities\":{\"platformName\":\"" + platform + "\",\"udid\":\"" + udid + "\"}}}");
            } else if (path.startsWith("/session/")) {
                String id = path.split("/")[2];
                if (!sessions.contains(id)) {
                    respond(exchange, 404, "{\"value\":{\"error\":\"invalid session id\",\"message\":\"unknown session " + id + "\"}}");
                } else if ("DELETE".equals(method) && path.equals("/session/" + id)) {
                    sessions.remove(id);
                    respond(exchange, 200, "{\"sessionId\":\"" + id + "\",\"status\":0,\"value\":null}");
                } else {
                    respond(exchange, 200, "{\"sessionId\":\"" + id + "\",\"status\":0,\"value\":{\"implicit\":0,\"pageLoad\":300000,\"script\":30000}}");
                }
            } else {
                respond(exchange, 404, "{\"value\":{\"error\":\"unknown command\",\"message\":\"" + method + " " + path + "\"}}");
            }
        } finally {
            exchange.close();
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        reRegistration.shutdownNow();
        server.stop(0);
    }
}
