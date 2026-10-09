package com.zebrunner.mcloud.grid.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contract tests for the load client.
 * Verifies that preemptive HTTP Basic Auth is attached to every request and that sensitive data is not leaked into reports.
 */
public class LoadClientTest {
    @Test
    public void sendsBasicAuthOnEveryRequestWhileCommandsRunForConfiguredTime() throws Exception {
        String user = "load-user";
        String password = "private-password";
        String expectedAuth = LoadConfig.basicAuthorization(user, password);
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger unauthorized = new AtomicInteger();
        HttpServer grid = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        grid.createContext("/wd/hub/session", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (!expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                unauthorized.incrementAndGet();
                respond(exchange, 401, "unauthorized");
            } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/session")) {
                respond(exchange, 200, "{\"sessionId\":\"mock-" + creates.incrementAndGet() + "\",\"status\":0}");
            } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/timeouts")) {
                commands.incrementAndGet();
                respond(exchange, 200, "{\"status\":0,\"value\":{}}");
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 200, "{\"status\":0}");
            } else {
                respond(exchange, 404, "not found");
            }
        });
        grid.start();

        Map<String, String> properties = new HashMap<>();
        String[] names = {"grid.url", "load.sessions", "load.concurrency", "load.commandDurationMs", "load.commandIntervalMs", "load.reportDir"};
        for (String name : names) {
            properties.put(name, System.getProperty(name));
        }
        Path report = Path.of("target/load-report/auth-test/summary.json");
        try {
            System.setProperty("grid.url", "http://127.0.0.1:" + grid.getAddress().getPort() + "/wd/hub");
            System.setProperty("load.sessions", "2");
            System.setProperty("load.concurrency", "2");
            System.setProperty("load.commandDurationMs", "180");
            System.setProperty("load.commandIntervalMs", "30");
            System.setProperty("load.reportDir", report.getParent().toString());
            LoadConfig config = LoadConfig.fromSystemProperties(user, password);
            Assert.assertFalse(config.toString().contains(password));
            GridLoadTest load = new GridLoadTest(config);
            try {
                load.setUp();
                load.sessionLifecycleUnderLoad();
            } finally {
                load.tearDown();
            }
            Assert.assertEquals(creates.get(), 2);
            Assert.assertEquals(deletes.get(), 2);
            Assert.assertTrue(commands.get() >= 4, "Expected multiple commands per session: " + commands.get());
            Assert.assertEquals(unauthorized.get(), 0);
            Assert.assertFalse(Files.readString(report).contains(password));
        } finally {
            grid.stop(0);
            properties.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }

    @Test
    public void requiresBothCredentials() {
        Assert.expectThrows(IllegalArgumentException.class, () -> LoadConfig.basicAuthorization("user", null));
        Assert.expectThrows(IllegalArgumentException.class, () -> LoadConfig.basicAuthorization(null, "password"));
        Assert.expectThrows(IllegalArgumentException.class, () -> LoadConfig.basicAuthorization("user:name", "password"));
        Assert.assertNull(LoadConfig.basicAuthorization(null, null));
    }

    @Test
    public void serializesFailedRequestsRegardlessOfLocale() throws Exception {
        String error = "HTTP 401: invalid \"credentials\"\naccess denied";
        LoadConfig config = LoadConfig.fromSystemProperties("user", "secret");
        LoadReport report = new LoadReport(List.of(new LoadReport.SessionResult(false, error, 10, List.of(), -1)), 50, Map.of());
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            JsonNode json = new ObjectMapper().readTree(report.toJson(config));
            Assert.assertEquals(json.get("errors").get(error).asInt(), 1);
            Assert.assertEquals(json.get("errorRate").asDouble(), 1.0);
            Assert.assertEquals(json.get("commandMs").get("count").asInt(), 0);
            Assert.assertFalse(json.toString().contains("secret"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, data.length);
        try (java.io.OutputStream stream = exchange.getResponseBody()) {
            stream.write(data);
        }
    }
}
